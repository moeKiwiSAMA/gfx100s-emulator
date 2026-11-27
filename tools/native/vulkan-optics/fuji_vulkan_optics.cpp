/*
 * FUJIFILM GFX100S Emulator - Cross-Platform Vulkan 1.1 Compute Optical Pipeline
 *
 * Pure C++17 runtime dynamically loading libvulkan.so.1 (Linux) or vulkan-1.dll (Windows)
 * via dlopen / LoadLibraryW — ZERO static Vulkan linkage, ZERO extra runtime DLLs.
 */

#include <cstdint>
#include "fuji_vulkan_uniresz_spirv.h"
#include "fuji_vulkan_optics.h"
#include "fuji_vulkan_stats.h"
#include "fuji_vk_abi.h"
#include "fuji_vulkan_pipeline_cache.h"
#include "fuji_vulkan_shaders_spirv.h"
#include "fuji_vulkan_stats_spirv.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <windows.h>
#else
#  include <dlfcn.h>
#endif

static_assert(sizeof(FujiVkOpticsParams) == 128, "FujiVkOpticsParams must be 128 bytes");
static_assert(sizeof(FujiVkCmosParams) == 64, "FujiVkCmosParams must be 64 bytes");
static_assert(offsetof(VkPhysicalDeviceProperties, deviceName) == 20, "VkPhysicalDeviceProperties::deviceName offset mismatch");
static_assert(sizeof(VkPhysicalDeviceProperties) == 824, "VkPhysicalDeviceProperties size mismatch");

namespace {

struct DynamicLib {
#if defined(_WIN32)
    HMODULE handle = nullptr;
    bool open(const char* name) {
        handle = LoadLibraryA(name);
        return handle != nullptr;
    }
    void* sym(const char* name) const {
        return handle ? reinterpret_cast<void*>(GetProcAddress(handle, name)) : nullptr;
    }
    void close() {
        if (handle) {
            FreeLibrary(handle);
            handle = nullptr;
        }
    }
#else
    void* handle = nullptr;
    bool open(const char* name) {
        handle = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        return handle != nullptr;
    }
    void* sym(const char* name) const {
        return handle ? dlsym(handle, name) : nullptr;
    }
    void close() {
        if (handle) {
            dlclose(handle);
            handle = nullptr;
        }
    }
#endif
    ~DynamicLib() { close(); }
};

struct VkBufferAlloc {
    VkBuffer       buffer = nullptr;
    VkDeviceMemory memory = nullptr;
    VkDeviceSize   capacity = 0;
    VkDeviceSize   allocSize = 0;
    void*          mapped = nullptr;
    bool           isHostCached = false;
    bool           isHostCoherent = true;
};

struct VkDispatchTable {
    PFN_vkGetInstanceProcAddr                  getInstanceProcAddr = nullptr;
    PFN_vkGetDeviceProcAddr                    getDeviceProcAddr = nullptr;
    PFN_vkEnumerateInstanceVersion             enumerateInstanceVersion = nullptr;
    PFN_vkCreateInstance                       createInstance = nullptr;
    PFN_vkDestroyInstance                      destroyInstance = nullptr;
    PFN_vkEnumeratePhysicalDevices             enumeratePhysicalDevices = nullptr;
    PFN_vkGetPhysicalDeviceProperties          getPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties getPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties    getPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties   enumerateDeviceExtensionProperties = nullptr;
    PFN_vkCreateDevice                         createDevice = nullptr;
    PFN_vkDestroyDevice                        destroyDevice = nullptr;
    PFN_vkGetDeviceQueue                       getDeviceQueue = nullptr;
    PFN_vkCreateBuffer                         createBuffer = nullptr;
    PFN_vkDestroyBuffer                        destroyBuffer = nullptr;
    PFN_vkGetBufferMemoryRequirements          getBufferMemoryRequirements = nullptr;
    PFN_vkAllocateMemory                       allocateMemory = nullptr;
    PFN_vkFreeMemory                           freeMemory = nullptr;
    PFN_vkBindBufferMemory                     bindBufferMemory = nullptr;
    PFN_vkMapMemory                            mapMemory = nullptr;
    PFN_vkUnmapMemory                          unmapMemory = nullptr;
    PFN_vkFlushMappedMemoryRanges              flushMappedMemoryRanges = nullptr;
    PFN_vkInvalidateMappedMemoryRanges         invalidateMappedMemoryRanges = nullptr;
    PFN_vkCreateShaderModule                   createShaderModule = nullptr;
    PFN_vkDestroyShaderModule                  destroyShaderModule = nullptr;
    PFN_vkCreatePipelineCache                  createPipelineCache = nullptr;
    PFN_vkDestroyPipelineCache                 destroyPipelineCache = nullptr;
    PFN_vkGetPipelineCacheData                 getPipelineCacheData = nullptr;
    PFN_vkCreateDescriptorSetLayout            createDescriptorSetLayout = nullptr;
    PFN_vkDestroyDescriptorSetLayout           destroyDescriptorSetLayout = nullptr;
    PFN_vkCreatePipelineLayout                 createPipelineLayout = nullptr;
    PFN_vkDestroyPipelineLayout                destroyPipelineLayout = nullptr;
    PFN_vkCreateComputePipelines               createComputePipelines = nullptr;
    PFN_vkDestroyPipeline                      destroyPipeline = nullptr;
    PFN_vkCreateDescriptorPool                 createDescriptorPool = nullptr;
    PFN_vkDestroyDescriptorPool                destroyDescriptorPool = nullptr;
    PFN_vkAllocateDescriptorSets               allocateDescriptorSets = nullptr;
    PFN_vkUpdateDescriptorSets                 updateDescriptorSets = nullptr;
    PFN_vkCreateCommandPool                    createCommandPool = nullptr;
    PFN_vkDestroyCommandPool                   destroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers               allocateCommandBuffers = nullptr;
    PFN_vkResetCommandBuffer                   resetCommandBuffer = nullptr;
    PFN_vkBeginCommandBuffer                   beginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer                     endCommandBuffer = nullptr;
    PFN_vkCmdBindPipeline                      cmdBindPipeline = nullptr;
    PFN_vkCmdBindDescriptorSets                cmdBindDescriptorSets = nullptr;
    PFN_vkCmdDispatch                          cmdDispatch = nullptr;
    PFN_vkCmdPipelineBarrier                   cmdPipelineBarrier = nullptr;
    PFN_vkCmdCopyBuffer                        cmdCopyBuffer = nullptr;
    PFN_vkCmdFillBuffer                        cmdFillBuffer = nullptr;
    PFN_vkCreateFence                          createFence = nullptr;
    PFN_vkDestroyFence                         destroyFence = nullptr;
    PFN_vkResetFences                          resetFences = nullptr;
    PFN_vkWaitForFences                        waitForFences = nullptr;
    PFN_vkQueueSubmit                          queueSubmit = nullptr;
    PFN_vkDeviceWaitIdle                       deviceWaitIdle = nullptr;
};

void write_err(char* buf, size_t len, const char* fmt, ...) {
    if (!buf || len == 0) return;
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, len, fmt, ap);
    va_end(ap);
}

int score_device_type(VkPhysicalDeviceType type, uint32_t select_flags) {
    switch (type) {
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:
            return (select_flags & FUJI_VK_SELECT_PREFER_DISCRETE) ? 1000 : 800;
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU:
            return (select_flags & FUJI_VK_SELECT_ALLOW_INTEGRATED) ? 500 : -1;
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:
            return (select_flags & FUJI_VK_SELECT_ALLOW_INTEGRATED) ? 300 : -1;
        case VK_PHYSICAL_DEVICE_TYPE_CPU:
            return (select_flags & FUJI_VK_SELECT_ALLOW_SOFTWARE) ? 100 : -1;
        default:
            return (select_flags & FUJI_VK_SELECT_ALLOW_SOFTWARE) ? 50 : -1;
    }
}

std::mutex  g_cache_path_mutex;
std::string g_pipeline_cache_path;

} /* namespace */

struct FujiVkExternalFrame {
    FujiVkExternalFrameDesc desc{};
    VkBuffer                colorBuffer = nullptr;
    VkDeviceMemory          colorMemory = nullptr;
    VkBuffer                depthBuffer = nullptr;
    VkDeviceMemory          depthMemory = nullptr;
};

struct FujiVkContext {
    static constexpr uint32_t kFrameSlots = 2u;
    static constexpr uint32_t kDescSetsPerSlot = 8u; /* 0=Warp, 1=Bokeh, 2=CMOS, 3=Filter, 4=ISP Demosaic, 5=ISP Peaking, 6=ISP Stats, 7=BokehDown2x */

    DynamicLib       loader;
    VkDispatchTable  vk{};
    FujiVkDeviceInfo info{};
    std::string      lastError;

    VkInstance                       instance = nullptr;
    VkPhysicalDevice                 physDev = nullptr;
    VkPhysicalDeviceMemoryProperties memProps{};
    VkDevice                         device = nullptr;
    VkQueue                          computeQueue = nullptr;
    uint32_t                         queueFamily = 0;

    VkCommandPool         cmdPool = nullptr;
    VkCommandBuffer       cmdBufs[kFrameSlots] = {nullptr, nullptr};
    VkFence               fences[kFrameSlots] = {nullptr, nullptr};
    uint32_t              slotCursor = 0u;

    VkDescriptorSetLayout descSetLayout = nullptr;
    VkPipelineLayout      pipelineLayout = nullptr;
    VkDescriptorPool      descPool = nullptr;
    VkDescriptorSet       descSets[kFrameSlots][kDescSetsPerSlot] = {};

    VkShaderModule        shaderModules[FUJI_VK_SHADER_COUNT] = {};
    VkPipeline       uniReszPipeline = nullptr;
    VkShaderModule   uniReszModule = nullptr;
    VkBufferAlloc    bufUniReszIn{}, bufUniReszOut{}, bufUniReszParams{};
    VkPipeline            pipelines[FUJI_VK_SHADER_COUNT] = {};
    VkShaderModule        statsShaderModule = nullptr;
    VkPipeline            statsPipeline = nullptr;
    VkPipelineCache       pipelineCache = nullptr;
    FujiVkPipelineCacheIdentity cacheIdentity{};
    std::string           cacheFilePath;
    bool                  cacheDirty = false;
    FujiVkPipelineStats   pipelineStats{};
    std::unordered_map<uint64_t, VkPipeline> specializedPipelines;
    static constexpr size_t kMaxSpecializedPipelines = 64u;

    /* Ring-2 isolated per-slot async state (GPU computes slot N while CPU reads slot N-1) */
    struct AsyncFrameSlot {
        bool          inFlight = false;
        uint64_t      ticket = 0ULL;
        size_t        dstPixels = 0u;
        uint32_t      bitDepth = 14u;
        std::chrono::steady_clock::time_point submitTime{};
        VkBufferAlloc bufIn{};
        VkBufferAlloc bufFilter{};
        VkBufferAlloc bufMid{};
        VkBufferAlloc bufOut{};
        VkBufferAlloc bufBayer{};
        VkBufferAlloc bufFilterUbo{};
        VkBufferAlloc bufOpticsUbo{};
        VkBufferAlloc bufCmosUbo{};
    };
    AsyncFrameSlot        asyncSlots[kFrameSlots]{};
    uint64_t              nextAsyncTicket = 1ULL;

    /* Reusable GPU buffer pool (high-water-mark monotonic sizing; zero churn across EVF/LCD switches) */
    VkBufferAlloc bufIn{};
    VkBufferAlloc bufSceneDevice{}; /* GPU-resident DEVICE_LOCAL VRAM scene cache for zero-upload focus sweeps */
    VkBufferAlloc bufFilter{};     /* GPU-only intermediate (device-local preferred) */
    VkBufferAlloc bufMid{};        /* GPU-only intermediate (device-local preferred) */
    VkBufferAlloc bufOut{};        /* Host-readable (HOST_CACHED preferred) when returning LinearRgb */
    VkBufferAlloc bufBayer{};      /* Host-readable (HOST_CACHED preferred) */
    VkBufferAlloc bufIspIn{};        /* HOST_VISIBLE staging for 1D LUT (16 KiB) / standalone Bayer upload */
    VkBufferAlloc bufIspInDevice{};  /* DEVICE_LOCAL VRAM: [0..4095]=packed 1D LUT, [4096..]=GPU-resident 12-bit Bayer */
    VkBufferAlloc bufIspMid{};       /* GPU-only intermediate before Focus Peaking */
    VkBufferAlloc bufIspOut{};       /* Host-readable (HOST_CACHED preferred) packed UYVY422 pairs */
    VkBufferAlloc bufFilterUbo{};
    VkBufferAlloc bufOpticsUbo{};
    VkBufferAlloc bufCmosUbo{};
    VkBufferAlloc bufIspUbo{};
    VkBufferAlloc bufIspStatsDevice{}; /* 9,280 B DEVICE_LOCAL VRAM target for Pass 6 atomicAdd (zero PCIe atomics!) */
    VkBufferAlloc bufIspStatsOut{};    /* 9,280 B HOST_CACHED readback buffer copied via vkCmdCopyBuffer */
    VkBufferAlloc bufIspStatsUbo{};    /* 96 B FujiVkIspStatsParams */

    uint32_t     cachedLutPacked[4096]{};
    bool         cachedLutDefault = false;
    bool         cachedLutValid = false;
    bool         cachedLutDeviceSynced = false;
    uint64_t     cachedSceneKey = 0ULL;
    VkDeviceSize cachedSceneBytes = 0ULL;
    bool         cachedSceneValid = false;
    uint64_t     cachedOpticsKey = 0ULL;
    VkDeviceSize cachedOpticsBytes = 0ULL;
    bool         cachedOpticsValid = false;
    FujiVkCmosParams cachedIspBayerParams{};
    bool         cachedIspBayerValid = false;

    void setError(const std::string& msg) { lastError = msg; }

    uint32_t nextSlot() {
        uint32_t s = slotCursor;
        slotCursor = (slotCursor + 1u) % kFrameSlots;
        return s;
    }

    int32_t findMemoryType(uint32_t typeBits, VkFlags requiredProps) const {
        for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
            if ((typeBits & (1u << i)) != 0 &&
                (memProps.memoryTypes[i].propertyFlags & requiredProps) == requiredProps) {
                return static_cast<int32_t>(i);
            }
        }
        return -1;
    }

    void freeBuffer(VkBufferAlloc& a) {
        // The driver can recycle an identical VkBuffer handle for a new allocation.
        if (&a == &bufIspIn) {
            cachedLutValid = false;
            cachedLutDeviceSynced = false;
        } else if (&a == &bufIspInDevice) {
            cachedLutDeviceSynced = false;
            cachedIspBayerValid = false;
        }
        if (device) {
            if (a.mapped && a.memory && vk.unmapMemory) {
                vk.unmapMemory(device, a.memory);
            }
            if (a.buffer && vk.destroyBuffer) {
                vk.destroyBuffer(device, a.buffer, nullptr);
            }
            if (a.memory && vk.freeMemory) {
                vk.freeMemory(device, a.memory, nullptr);
            }
        }
        a = VkBufferAlloc{};
    }

    void invalidateReadback(const VkBufferAlloc& a) const {
        if (!a.memory || a.isHostCoherent || !vk.invalidateMappedMemoryRanges) return;
        VkMappedMemoryRange range{};
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = a.memory;
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        vk.invalidateMappedMemoryRanges(device, 1, &range);
    }

    bool allocateBufferWithMemory(
        VkBufferAlloc& a,
        VkDeviceSize allocCap,
        VkFlags usage,
        int32_t memIdx,
        bool mapHost) {
        VkBufferCreateInfo bci{};
        bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bci.size = allocCap;
        bci.usage = usage;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

        VkResult r = vk.createBuffer(device, &bci, nullptr, &a.buffer);
        if (r != VK_SUCCESS) {
            setError("vkCreateBuffer failed: " + std::to_string(r));
            return false;
        }
        VkMemoryRequirements req{};
        vk.getBufferMemoryRequirements(device, a.buffer, &req);

        /* Re-validate requested memory index against actual buffer requirements */
        if (memIdx < 0 || (req.memoryTypeBits & (1u << static_cast<uint32_t>(memIdx))) == 0) {
            freeBuffer(a);
            setError("Incompatible Vulkan memory type index");
            return false;
        }

        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = static_cast<uint32_t>(memIdx);

        r = vk.allocateMemory(device, &mai, nullptr, &a.memory);
        if (r != VK_SUCCESS) {
            freeBuffer(a);
            setError("vkAllocateMemory failed: " + std::to_string(r));
            return false;
        }
        r = vk.bindBufferMemory(device, a.buffer, a.memory, 0);
        if (r != VK_SUCCESS) {
            freeBuffer(a);
            setError("vkBindBufferMemory failed: " + std::to_string(r));
            return false;
        }
        const VkFlags props = memProps.memoryTypes[memIdx].propertyFlags;
        a.isHostCached = (props & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) != 0;
        a.isHostCoherent = (props & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
        a.allocSize = req.size;
        if (mapHost) {
            r = vk.mapMemory(device, a.memory, 0, VK_WHOLE_SIZE, 0, &a.mapped);
            if (r != VK_SUCCESS || !a.mapped) {
                freeBuffer(a);
                setError("vkMapMemory failed: " + std::to_string(r));
                return false;
            }
        } else {
            a.mapped = nullptr;
        }
        a.capacity = allocCap;
        pipelineStats.bufferReallocCount++;
        return true;
    }

    bool ensureHostBuffer(VkBufferAlloc& a, VkDeviceSize neededBytes, VkFlags usage) {
        if (neededBytes == 0) neededBytes = 64;
        bool shouldRealloc = false;
        VkDeviceSize allocCap = fuji_vk_hwm_capacity(a.capacity, neededBytes, shouldRealloc);
        if (a.buffer && a.mapped && !shouldRealloc) return true;
        freeBuffer(a);

        VkBufferCreateInfo probe{};
        probe.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        probe.size = allocCap;
        probe.usage = usage;
        probe.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkBuffer tmpBuf = nullptr;
        if (vk.createBuffer(device, &probe, nullptr, &tmpBuf) != VK_SUCCESS) {
            setError("vkCreateBuffer (host probe) failed");
            return false;
        }
        VkMemoryRequirements req{};
        vk.getBufferMemoryRequirements(device, tmpBuf, &req);
        vk.destroyBuffer(device, tmpBuf, nullptr);

        /* Prefer DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT (ReBAR / unified iGPU / Lavapipe) first */
        int32_t memIdx = findMemoryType(
            req.memoryTypeBits,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (memIdx < 0) {
            memIdx = findMemoryType(
                req.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        }
        if (memIdx < 0) {
            setError("No HOST_VISIBLE | HOST_COHERENT Vulkan memory type available");
            return false;
        }
        return allocateBufferWithMemory(a, allocCap, usage, memIdx, true);
    }

    /*
     * Allocates GPU->CPU readback staging buffer with high-water-mark sizing, explicitly preferring
     * HOST_VISIBLE | HOST_CACHED (for fast CPU memcpy/packing on discrete GPUs like GTX 1650;
     * calls vkInvalidateMappedMemoryRanges before CPU read when non-coherent), falling back cleanly
     * to HOST_VISIBLE | HOST_COHERENT when HOST_CACHED is unavailable.
     */
    bool ensureReadbackBuffer(VkBufferAlloc& a, VkDeviceSize neededBytes, VkFlags usage) {
        if (neededBytes == 0) neededBytes = 64;
        bool shouldRealloc = false;
        VkDeviceSize allocCap = fuji_vk_hwm_capacity(a.capacity, neededBytes, shouldRealloc);
        if (a.buffer && a.mapped && !shouldRealloc) return true;
        freeBuffer(a);

        VkBufferCreateInfo probe{};
        probe.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        probe.size = allocCap;
        probe.usage = usage;
        probe.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkBuffer tmpBuf = nullptr;
        if (vk.createBuffer(device, &probe, nullptr, &tmpBuf) != VK_SUCCESS) {
            setError("vkCreateBuffer (readback probe) failed");
            return false;
        }
        VkMemoryRequirements req{};
        vk.getBufferMemoryRequirements(device, tmpBuf, &req);
        vk.destroyBuffer(device, tmpBuf, nullptr);

        int32_t memIdx = findMemoryType(
            req.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (memIdx < 0 && vk.invalidateMappedMemoryRanges) {
            memIdx = findMemoryType(
                req.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
        }
        if (memIdx < 0) {
            memIdx = findMemoryType(
                req.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        }
        if (memIdx < 0) {
            setError("No HOST_VISIBLE Vulkan readback memory type available");
            return false;
        }
        if (!allocateBufferWithMemory(a, allocCap, usage, memIdx, true)) {
            return false;
        }
        if (a.isHostCached) {
            pipelineStats.hostCachedReadback = 1u;
        }
        return true;
    }

    /* Allocates GPU-only intermediate VRAM buffer (DEVICE_LOCAL preferred, high-water-mark sizing). */
    bool ensureDeviceBuffer(VkBufferAlloc& a, VkDeviceSize neededBytes, VkFlags usage) {
        if (neededBytes == 0) neededBytes = 64;
        bool shouldRealloc = false;
        VkDeviceSize allocCap = fuji_vk_hwm_capacity(a.capacity, neededBytes, shouldRealloc);
        if (a.buffer && !shouldRealloc) return true;
        freeBuffer(a);

        VkBufferCreateInfo probe{};
        probe.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        probe.size = allocCap;
        probe.usage = usage;
        probe.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkBuffer tmpBuf = nullptr;
        if (vk.createBuffer(device, &probe, nullptr, &tmpBuf) != VK_SUCCESS) {
            setError("vkCreateBuffer (device probe) failed");
            return false;
        }
        VkMemoryRequirements req{};
        vk.getBufferMemoryRequirements(device, tmpBuf, &req);
        vk.destroyBuffer(device, tmpBuf, nullptr);

        int32_t memIdx = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (memIdx < 0) {
            memIdx = findMemoryType(
                req.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        }
        if (memIdx < 0) {
            setError("No DEVICE_LOCAL or HOST_VISIBLE Vulkan memory type available");
            return false;
        }
        return allocateBufferWithMemory(a, allocCap, usage, memIdx, false);
    }

    VkPipeline getOrCreateSpecializedPipeline(
        uint32_t shaderId,
        uint64_t variantKey,
        const VkSpecializationMapEntry* entries,
        uint32_t entryCount,
        const void* specData,
        size_t specDataSize) {
        if (shaderId >= FUJI_VK_SHADER_COUNT || !shaderModules[shaderId]) {
            return nullptr;
        }
        const uint64_t fullKey = (static_cast<uint64_t>(shaderId) << 56) | (variantKey & 0x00FF'FFFF'FFFF'FFFFULL);
        auto it = specializedPipelines.find(fullKey);
        if (it != specializedPipelines.end()) {
            return it->second;
        }
        if (specializedPipelines.size() >= kMaxSpecializedPipelines) {
            /* Graceful fallback to base dynamic pipeline once variant cap is reached */
            return pipelines[shaderId];
        }

        VkSpecializationInfo specInfo{};
        specInfo.mapEntryCount = entryCount;
        specInfo.pMapEntries = entries;
        specInfo.dataSize = specDataSize;
        specInfo.pData = specData;

        VkComputePipelineCreateInfo cpci{};
        cpci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        cpci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        cpci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        cpci.stage.module = shaderModules[shaderId];
        cpci.stage.pName = "main";
        cpci.stage.pSpecializationInfo = (entryCount > 0u) ? &specInfo : nullptr;
        cpci.layout = pipelineLayout;

        VkPipeline pipe = nullptr;
        VkResult r = vk.createComputePipelines(device, pipelineCache, 1, &cpci, nullptr, &pipe);
        if (r != VK_SUCCESS || !pipe) {
            return pipelines[shaderId];
        }
        specializedPipelines[fullKey] = pipe;
        pipelineStats.specializedPipelineCount = static_cast<uint32_t>(specializedPipelines.size());
        cacheDirty = true;
        return pipe;
    }

    VkPipeline getSpecializedWarpPipeline(const FujiVkOpticsParams* optics) {
        const uint32_t borderClamp = (optics->borderClamp != 0u) ? 1u : 0u;
        const uint32_t hasCa = (std::fabs(optics->caRedScale - 1.0f) > 1e-5f || std::fabs(optics->caBlueScale - 1.0f) > 1e-5f) ? 1u : 0u;
        const float mmPerPx = optics->sensorWidthMm / static_cast<float>(std::max(1u, optics->dstWidth));
        const float maxHalfW = 0.5f * optics->sensorWidthMm + std::fabs(optics->ibisShiftX) * mmPerPx;
        const float maxHalfH = (0.5f * static_cast<float>(optics->dstHeight) + std::fabs(optics->ibisShiftY)) * mmPerPx;
        const float onsetRadius = 0.44f * optics->imageCircleMm;
        const bool clipsCircle = onsetRadius > 0.0f &&
            (maxHalfW * maxHalfW + maxHalfH * maxHalfH) > (onsetRadius * onsetRadius);
        const uint32_t hasVig = (clipsCircle || optics->cos4Power > 1e-5f || optics->mechVignetting > 1e-5f) ? 1u : 0u;
        const uint32_t data[3] = {borderClamp, hasCa, hasVig};
        const VkSpecializationMapEntry entries[3] = {
            {0u, 0u, sizeof(uint32_t)},
            {1u, 4u, sizeof(uint32_t)},
            {2u, 8u, sizeof(uint32_t)},
        };
        const uint64_t key = (static_cast<uint64_t>(borderClamp) << 0) |
                             (static_cast<uint64_t>(hasCa) << 1) |
                             (static_cast<uint64_t>(hasVig) << 2);
        return getOrCreateSpecializedPipeline(FUJI_VK_SHADER_OPTICS_WARP, key, entries, 3u, data, sizeof(data));
    }

    VkPipeline getSpecializedBokehPipeline(const FujiVkOpticsParams* optics) {
        const uint32_t shape = (optics->pupilShape <= FUJI_VK_PUPIL_RING) ? optics->pupilShape : FUJI_VK_PUPIL_CIRCLE;
        const VkSpecializationMapEntry entry = {0u, 0u, sizeof(uint32_t)};
        return getOrCreateSpecializedPipeline(FUJI_VK_SHADER_PUPIL_BOKEH, static_cast<uint64_t>(shape), &entry, 1u, &shape, sizeof(shape));
    }

    VkPipeline getSpecializedIspDemosaicPipeline(const FujiVkIspDemosaicParams& p) {
        const uint32_t outFmt = (p.outputFormat <= FUJI_VK_ISP_FMT_YYUV422) ? p.outputFormat : FUJI_VK_ISP_FMT_UYVY422;
        const uint32_t peakOn = ((p.flags & FUJI_VK_ISP_FLAG_PEAKING_ENABLED) != 0u) ? 1u : 0u;
        const uint32_t is16 = (p.is16Bit != 0u) ? 1u : 0u;
        const uint32_t modeMask = p.flags & 7u;
        const uint32_t data[4] = {outFmt, peakOn, is16, modeMask};
        const VkSpecializationMapEntry entries[4] = {
            {0u, 0u, sizeof(uint32_t)},
            {1u, 4u, sizeof(uint32_t)},
            {2u, 8u, sizeof(uint32_t)},
            {3u, 12u, sizeof(uint32_t)},
        };
        const uint64_t key = (static_cast<uint64_t>(outFmt) << 0) |
                             (static_cast<uint64_t>(peakOn) << 3) |
                             (static_cast<uint64_t>(is16) << 4) |
                             (static_cast<uint64_t>(modeMask) << 5);
        return getOrCreateSpecializedPipeline(FUJI_VK_SHADER_ISP_DEMOSAIC, key, entries, 4u, data, sizeof(data));
    }

    void flushPipelineCacheToDisk() {
        if (!device || !pipelineCache || !vk.getPipelineCacheData || cacheFilePath.empty()) return;
        size_t blobSize = 0;
        if (vk.getPipelineCacheData(device, pipelineCache, &blobSize, nullptr) != VK_SUCCESS || blobSize == 0) {
            return;
        }
        std::vector<uint8_t> blob(blobSize);
        if (vk.getPipelineCacheData(device, pipelineCache, &blobSize, blob.data()) != VK_SUCCESS || blobSize == 0) {
            return;
        }
        pipelineStats.pipelineCacheBytes = static_cast<uint64_t>(blobSize);
        if (fuji_vk_save_pipeline_cache_blob(cacheFilePath, cacheIdentity, blob.data(), blobSize)) {
            cacheDirty = false;
        }
    }

    void prewarmDefaultLiveView() {
        /* 1. Pre-compile common LiveView specialized pipelines into VkPipelineCache */
        FujiVkOpticsParams wDef{};
        wDef.borderClamp = 0u; wDef.caRedScale = 1.0f; wDef.caBlueScale = 1.0f; wDef.cos4Power = 2.0f; wDef.mechVignetting = 0.2f;
        getSpecializedWarpPipeline(&wDef);
        wDef.borderClamp = 1u;
        getSpecializedWarpPipeline(&wDef);
        wDef.caRedScale = 1.002f;
        getSpecializedWarpPipeline(&wDef);

        FujiVkOpticsParams bDef{};
        bDef.pupilShape = FUJI_VK_PUPIL_CIRCLE;
        getSpecializedBokehPipeline(&bDef);
        bDef.pupilShape = FUJI_VK_PUPIL_POLYGON;
        getSpecializedBokehPipeline(&bDef);

        FujiVkIspDemosaicParams iDef{};
        iDef.is16Bit = 1u; iDef.outputFormat = FUJI_VK_ISP_FMT_UYVY422; iDef.flags = FUJI_VK_ISP_FLAG_HAS_CCM;
        getSpecializedIspDemosaicPipeline(iDef);
        iDef.outputFormat = FUJI_VK_ISP_FMT_YYUV422;
        getSpecializedIspDemosaicPipeline(iDef);

        /* 2. Pre-allocate default 1024x576 LiveView high-water-mark buffers so frame 0 has zero alloc stutter */
        constexpr VkDeviceSize kDefaultLvPixels = 1024ULL * 576ULL;
        constexpr VkDeviceSize kDefaultMidBytes = (((kDefaultLvPixels + 15ULL) & ~15ULL) + (512ULL * 288ULL)) * 16ULL;
        ensureHostBuffer(bufIn, kDefaultLvPixels * 16ULL, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        ensureDeviceBuffer(bufMid, kDefaultMidBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        ensureDeviceBuffer(bufOut, kDefaultLvPixels * 16ULL, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        ensureReadbackBuffer(bufBayer, kDefaultLvPixels * 4ULL, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        ensureReadbackBuffer(bufIspOut, (kDefaultLvPixels / 2ULL) * 4ULL, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    }

    void drainAllAsyncSlots() {
        if (!device || !vk.waitForFences) return;
        for (uint32_t s = 0; s < kFrameSlots; ++s) {
            if (asyncSlots[s].inFlight) {
                vk.waitForFences(device, 1, &fences[s], VK_TRUE, 10000000000ULL);
                asyncSlots[s].inFlight = false;
                asyncSlots[s].ticket = 0ULL;
            }
        }
    }

    void updateDescriptorSetWithOffsets(
        VkDescriptorSet ds,
        VkBuffer b0, VkDeviceSize off0, VkDeviceSize s0,
        VkBuffer b1, VkDeviceSize off1, VkDeviceSize s1,
        VkBuffer ubo, VkDeviceSize uboSize) {
        VkDescriptorBufferInfo bi[3]{};
        bi[0] = {b0, off0, s0};
        bi[1] = {b1, off1, s1};
        bi[2] = {ubo, 0, uboSize};

        VkWriteDescriptorSet writes[3]{};
        for (uint32_t i = 0; i < 3; ++i) {
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[i].dstSet = ds;
            writes[i].dstBinding = i;
            writes[i].descriptorCount = 1;
            writes[i].descriptorType = (i == 2) ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[i].pBufferInfo = &bi[i];
        }
        vk.updateDescriptorSets(device, 3, writes, 0, nullptr);
    }

    void updateDescriptorSet(VkDescriptorSet ds, VkBuffer b0, VkDeviceSize s0, VkBuffer b1, VkDeviceSize s1, VkBuffer ubo, VkDeviceSize uboSize) {
        updateDescriptorSetWithOffsets(ds, b0, 0, s0, b1, 0, s1, ubo, uboSize);
    }

    bool submitAndWait(uint32_t slot) {
        vk.resetFences(device, 1, &fences[slot]);
        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmdBufs[slot];
        VkResult r = vk.queueSubmit(computeQueue, 1, &si, fences[slot]);
        if (r != VK_SUCCESS) {
            setError("vkQueueSubmit failed: " + std::to_string(r));
            return false;
        }
        r = vk.waitForFences(device, 1, &fences[slot], VK_TRUE, 10000000000ULL /* 10s */);
        if (r != VK_SUCCESS) {
            setError("vkWaitForFences failed: " + std::to_string(r));
            return false;
        }
        return true;
    }

    void cleanup() {
        if (device && vk.deviceWaitIdle) {
            vk.deviceWaitIdle(device);
        }
        if (cacheDirty) {
            flushPipelineCacheToDisk();
        }
        for (uint32_t s = 0; s < kFrameSlots; ++s) {
            freeBuffer(asyncSlots[s].bufIn);
            freeBuffer(asyncSlots[s].bufFilter);
            freeBuffer(asyncSlots[s].bufMid);
            freeBuffer(asyncSlots[s].bufOut);
            freeBuffer(asyncSlots[s].bufBayer);
            freeBuffer(asyncSlots[s].bufFilterUbo);
            freeBuffer(asyncSlots[s].bufOpticsUbo);
            freeBuffer(asyncSlots[s].bufCmosUbo);
            asyncSlots[s].inFlight = false;
            asyncSlots[s].ticket = 0ULL;
        }
        freeBuffer(bufUniReszIn);
        freeBuffer(bufUniReszOut);
        freeBuffer(bufUniReszParams);
        if (uniReszPipeline) vk.destroyPipeline(device, uniReszPipeline, nullptr);
        if (uniReszModule) vk.destroyShaderModule(device, uniReszModule, nullptr);
        freeBuffer(bufIn);
        freeBuffer(bufSceneDevice);
        freeBuffer(bufFilter);
        freeBuffer(bufMid);
        freeBuffer(bufOut);
        freeBuffer(bufBayer);
        freeBuffer(bufIspIn);
        freeBuffer(bufIspInDevice);
        freeBuffer(bufIspMid);
        freeBuffer(bufIspOut);
        freeBuffer(bufFilterUbo);
        freeBuffer(bufOpticsUbo);
        freeBuffer(bufCmosUbo);
        freeBuffer(bufIspUbo);
        freeBuffer(bufIspStatsDevice);
        freeBuffer(bufIspStatsOut);
        freeBuffer(bufIspStatsUbo);
        cachedLutValid = false;
        cachedLutDeviceSynced = false;
        cachedSceneValid = false;
        cachedSceneKey = 0ULL;
        cachedSceneBytes = 0ULL;
        cachedOpticsValid = false;
        cachedOpticsKey = 0ULL;
        cachedOpticsBytes = 0ULL;
        if (device) {
            for (auto& kv : specializedPipelines) {
                if (kv.second && vk.destroyPipeline) vk.destroyPipeline(device, kv.second, nullptr);
            }
            specializedPipelines.clear();
            if (statsPipeline && vk.destroyPipeline) vk.destroyPipeline(device, statsPipeline, nullptr);
            if (statsShaderModule && vk.destroyShaderModule) vk.destroyShaderModule(device, statsShaderModule, nullptr);
            statsPipeline = nullptr;
            statsShaderModule = nullptr;
            for (uint32_t i = 0; i < FUJI_VK_SHADER_COUNT; ++i) {
                if (pipelines[i] && vk.destroyPipeline) vk.destroyPipeline(device, pipelines[i], nullptr);
                if (shaderModules[i] && vk.destroyShaderModule) vk.destroyShaderModule(device, shaderModules[i], nullptr);
                pipelines[i] = nullptr;
                shaderModules[i] = nullptr;
            }
            if (pipelineCache && vk.destroyPipelineCache) {
                vk.destroyPipelineCache(device, pipelineCache, nullptr);
                pipelineCache = nullptr;
            }
            if (descPool && vk.destroyDescriptorPool) vk.destroyDescriptorPool(device, descPool, nullptr);
            if (pipelineLayout && vk.destroyPipelineLayout) vk.destroyPipelineLayout(device, pipelineLayout, nullptr);
            if (descSetLayout && vk.destroyDescriptorSetLayout) vk.destroyDescriptorSetLayout(device, descSetLayout, nullptr);
            for (uint32_t s = 0; s < kFrameSlots; ++s) {
                if (fences[s] && vk.destroyFence) vk.destroyFence(device, fences[s], nullptr);
                fences[s] = nullptr;
            }
            if (cmdPool && vk.destroyCommandPool) vk.destroyCommandPool(device, cmdPool, nullptr);
            if (vk.destroyDevice) vk.destroyDevice(device, nullptr);
            device = nullptr;
        }
        if (instance && vk.destroyInstance) {
            vk.destroyInstance(instance, nullptr);
            instance = nullptr;
        }
        loader.close();
    }

    ~FujiVkContext() { cleanup(); }
};

namespace {

template <typename Fn>
bool load_inst_fn(const VkDispatchTable& vk, VkInstance inst, const char* name, Fn& out) {
    out = reinterpret_cast<Fn>(vk.getInstanceProcAddr(inst, name));
    return out != nullptr;
}

template <typename Fn>
bool load_dev_fn(const VkDispatchTable& vk, VkDevice dev, const char* name, Fn& out) {
    out = reinterpret_cast<Fn>(vk.getDeviceProcAddr(dev, name));
    return out != nullptr;
}

bool create_compute_pipeline(
    FujiVkContext* ctx,
    const uint32_t* words,
    uint32_t word_count,
    VkShaderModule& out_mod,
    VkPipeline& out_pipe) {
    VkShaderModuleCreateInfo smci{};
    smci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smci.codeSize = static_cast<size_t>(word_count) * sizeof(uint32_t);
    smci.pCode = words;
    VkResult r = ctx->vk.createShaderModule(ctx->device, &smci, nullptr, &out_mod);
    if (r != VK_SUCCESS) {
        ctx->setError("vkCreateShaderModule failed: " + std::to_string(r));
        return false;
    }
    VkComputePipelineCreateInfo cpci{};
    cpci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cpci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpci.stage.module = out_mod;
    cpci.stage.pName = "main";
    cpci.layout = ctx->pipelineLayout;
    r = ctx->vk.createComputePipelines(ctx->device, ctx->pipelineCache, 1, &cpci, nullptr, &out_pipe);
    if (r != VK_SUCCESS) {
        ctx->setError("vkCreateComputePipelines failed: " + std::to_string(r));
        return false;
    }
    return true;
}

void pack_bayer_u32_to_bytes(
    const uint32_t* src,
    uint8_t* dst,
    size_t pixel_count,
    uint32_t bit_depth) {
    if (bit_depth <= 8) {
        for (size_t i = 0; i < pixel_count; ++i) {
            dst[i] = static_cast<uint8_t>(src[i] & 0xFFu);
        }
    } else {
        for (size_t i = 0; i < pixel_count; ++i) {
            uint32_t v = src[i] & 0xFFFFu;
            dst[i * 2]     = static_cast<uint8_t>(v & 0xFFu);
            dst[i * 2 + 1] = static_cast<uint8_t>((v >> 8) & 0xFFu);
        }
    }
}

} /* namespace */

extern "C" {

FUJI_VK_EXPORT uint32_t fuji_vk_supports_pupil_transform(void) {
    return 1u;
}

FUJI_VK_EXPORT uint32_t fuji_vk_abi_version(void) {
    return FUJI_VK_OPTICS_ABI_VERSION;
}

FUJI_VK_EXPORT int32_t fuji_vk_get_spirv_words(
    uint32_t shader_id,
    const uint32_t** out_words,
    uint32_t* out_word_count) {
    if (!out_words || !out_word_count) return FUJI_VK_ERR_INVALID_ARG;
    switch (shader_id) {
        case FUJI_VK_SHADER_OPTICS_WARP:
            *out_words = FUJI_SPIRV_OPTICS_WARP;
            *out_word_count = static_cast<uint32_t>(sizeof(FUJI_SPIRV_OPTICS_WARP) / sizeof(uint32_t));
            return FUJI_VK_OK;
        case FUJI_VK_SHADER_PUPIL_BOKEH:
            *out_words = FUJI_SPIRV_PUPIL_BOKEH;
            *out_word_count = static_cast<uint32_t>(sizeof(FUJI_SPIRV_PUPIL_BOKEH) / sizeof(uint32_t));
            return FUJI_VK_OK;
        case FUJI_VK_SHADER_CMOS_BAYER:
            *out_words = FUJI_SPIRV_CMOS_BAYER;
            *out_word_count = static_cast<uint32_t>(sizeof(FUJI_SPIRV_CMOS_BAYER) / sizeof(uint32_t));
            return FUJI_VK_OK;
        case FUJI_VK_SHADER_OPTICAL_FILTER_STACK:
            *out_words = FUJI_SPIRV_OPTICAL_FILTER_STACK;
            *out_word_count = static_cast<uint32_t>(sizeof(FUJI_SPIRV_OPTICAL_FILTER_STACK) / sizeof(uint32_t));
            return FUJI_VK_OK;
        case FUJI_VK_SHADER_ISP_DEMOSAIC:
            *out_words = FUJI_SPIRV_ISP_DEMOSAIC;
            *out_word_count = static_cast<uint32_t>(sizeof(FUJI_SPIRV_ISP_DEMOSAIC) / sizeof(uint32_t));
            return FUJI_VK_OK;
        case FUJI_VK_SHADER_ISP_PEAKING:
            *out_words = FUJI_SPIRV_ISP_PEAKING;
            *out_word_count = static_cast<uint32_t>(sizeof(FUJI_SPIRV_ISP_PEAKING) / sizeof(uint32_t));
            return FUJI_VK_OK;
        case FUJI_VK_SHADER_BOKEH_DOWNSAMPLE:
            *out_words = FUJI_SPIRV_BOKEH_DOWNSAMPLE;
            *out_word_count = static_cast<uint32_t>(sizeof(FUJI_SPIRV_BOKEH_DOWNSAMPLE) / sizeof(uint32_t));
            return FUJI_VK_OK;
        default:
            return FUJI_VK_ERR_INVALID_ARG;
    }
}

static int32_t find_compute_queue_family(const VkDispatchTable& vk, VkPhysicalDevice pd) {
    uint32_t qcount = 0;
    vk.getPhysicalDeviceQueueFamilyProperties(pd, &qcount, nullptr);
    if (qcount == 0) return -1;
    std::vector<VkQueueFamilyProperties> qprops(qcount);
    vk.getPhysicalDeviceQueueFamilyProperties(pd, &qcount, qprops.data());

    int32_t chosen_q = -1;
    for (uint32_t q = 0; q < qcount; ++q) {
        if ((qprops[q].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
            chosen_q = static_cast<int32_t>(q);
            /* Prefer dedicated compute queue if available */
            if ((qprops[q].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0) break;
        }
    }
    return chosen_q;
}

static uint32_t query_external_memory_caps(const VkDispatchTable& vk, VkPhysicalDevice pd) {
    uint32_t ext_count = 0;
    uint32_t ext_caps = 0;
    if (vk.enumerateDeviceExtensionProperties(pd, nullptr, &ext_count, nullptr) == VK_SUCCESS && ext_count > 0) {
        std::vector<VkExtensionProperties> exts(ext_count);
        if (vk.enumerateDeviceExtensionProperties(pd, nullptr, &ext_count, exts.data()) == VK_SUCCESS) {
            for (const auto& e : exts) {
                if (std::strcmp(e.extensionName, "VK_KHR_external_memory_win32") == 0) ext_caps |= FUJI_VK_EXT_CAP_MEMORY_WIN32;
                else if (std::strcmp(e.extensionName, "VK_KHR_external_memory_fd") == 0) ext_caps |= FUJI_VK_EXT_CAP_MEMORY_FD;
                else if (std::strcmp(e.extensionName, "VK_EXT_external_memory_dma_buf") == 0) ext_caps |= FUJI_VK_EXT_CAP_DMA_BUF;
                else if (std::strcmp(e.extensionName, "VK_KHR_external_semaphore_win32") == 0) ext_caps |= FUJI_VK_EXT_CAP_SEMAPHORE_WIN32;
                else if (std::strcmp(e.extensionName, "VK_KHR_external_semaphore_fd") == 0) ext_caps |= FUJI_VK_EXT_CAP_SEMAPHORE_FD;
            }
        }
    }
    return ext_caps;
}

static int32_t open_vulkan_instance(
    DynamicLib& loader,
    VkDispatchTable& vk,
    VkInstance& out_instance,
    char* loader_name_buf,
    size_t loader_name_len,
    char* err_buf,
    size_t err_buf_len) {
#if defined(_WIN32)
    const char* candidates[] = {"vulkan-1.dll"};
#elif defined(__APPLE__)
    const char* candidates[] = {"libMoltenVK.dylib", "libvulkan.1.dylib", "libvulkan.dylib"};
#else
    const char* candidates[] = {"libvulkan.so.1", "libvulkan.so"};
#endif

    const char* loaded_name = nullptr;
    for (const char* name : candidates) {
        if (loader.open(name)) {
            loaded_name = name;
            break;
        }
    }
    if (!loaded_name) {
        write_err(err_buf, err_buf_len, "Vulkan loader library not found on host");
        return FUJI_VK_ERR_NO_LOADER;
    }
    if (loader_name_buf && loader_name_len > 0) {
        std::snprintf(loader_name_buf, loader_name_len, "%s", loaded_name);
    }

    vk.getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(loader.sym("vkGetInstanceProcAddr"));
    if (!vk.getInstanceProcAddr) {
        write_err(err_buf, err_buf_len, "vkGetInstanceProcAddr missing in %s", loaded_name);
        return FUJI_VK_ERR_NO_LOADER;
    }

    vk.enumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vk.getInstanceProcAddr(nullptr, "vkEnumerateInstanceVersion"));
    vk.createInstance = reinterpret_cast<PFN_vkCreateInstance>(
        vk.getInstanceProcAddr(nullptr, "vkCreateInstance"));
    if (!vk.createInstance) {
        write_err(err_buf, err_buf_len, "vkCreateInstance missing in %s", loaded_name);
        return FUJI_VK_ERR_NO_LOADER;
    }

    uint32_t loader_ver = VK_API_VERSION_1_0;
    if (vk.enumerateInstanceVersion) {
        uint32_t v = 0;
        if (vk.enumerateInstanceVersion(&v) == VK_SUCCESS) loader_ver = v;
    }
    uint32_t req_ver = (loader_ver >= VK_API_VERSION_1_1) ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;

    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "fuji-emulator-gfx100s";
    app.applicationVersion = 1;
    app.pEngineName = "fuji-vulkan-optics";
    app.engineVersion = FUJI_VK_OPTICS_ABI_VERSION;
    app.apiVersion = req_ver;

    VkInstanceCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &app;

    VkResult r = vk.createInstance(&ici, nullptr, &out_instance);
    if (r != VK_SUCCESS) {
        write_err(err_buf, err_buf_len, "vkCreateInstance failed (VkResult=%d)", r);
        return FUJI_VK_ERR_INIT_FAILED;
    }

    bool ok =
        load_inst_fn(vk, out_instance, "vkDestroyInstance", vk.destroyInstance) &&
        load_inst_fn(vk, out_instance, "vkEnumeratePhysicalDevices", vk.enumeratePhysicalDevices) &&
        load_inst_fn(vk, out_instance, "vkGetPhysicalDeviceProperties", vk.getPhysicalDeviceProperties) &&
        load_inst_fn(vk, out_instance, "vkGetPhysicalDeviceQueueFamilyProperties", vk.getPhysicalDeviceQueueFamilyProperties) &&
        load_inst_fn(vk, out_instance, "vkGetPhysicalDeviceMemoryProperties", vk.getPhysicalDeviceMemoryProperties) &&
        load_inst_fn(vk, out_instance, "vkEnumerateDeviceExtensionProperties", vk.enumerateDeviceExtensionProperties) &&
        load_inst_fn(vk, out_instance, "vkCreateDevice", vk.createDevice) &&
        load_inst_fn(vk, out_instance, "vkGetDeviceProcAddr", vk.getDeviceProcAddr);
    if (!ok) {
        write_err(err_buf, err_buf_len, "Required Vulkan instance functions missing");
        return FUJI_VK_ERR_INIT_FAILED;
    }
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_enumerate_devices(
    FujiVkDeviceInfo* out_devices,
    int32_t max_devices,
    int32_t* out_total,
    char* err_buf,
    int32_t err_buf_len) {
    const size_t err_len = (err_buf_len > 0) ? static_cast<size_t>(err_buf_len) : 0u;
    if (out_total) *out_total = 0;
    if (max_devices < 0 || (max_devices > 0 && !out_devices)) {
        write_err(err_buf, err_len, "Invalid out_devices / max_devices argument");
        return FUJI_VK_ERR_INVALID_ARG;
    }

    DynamicLib loader;
    VkDispatchTable vk{};
    VkInstance instance = nullptr;
    char loader_name[128] = {0};

    int32_t init_rc = open_vulkan_instance(loader, vk, instance, loader_name, sizeof(loader_name), err_buf, err_len);
    if (init_rc != FUJI_VK_OK) {
        if (instance && vk.destroyInstance) vk.destroyInstance(instance, nullptr);
        return init_rc;
    }

    uint32_t dev_count = 0;
    VkResult r = vk.enumeratePhysicalDevices(instance, &dev_count, nullptr);
    if (r != VK_SUCCESS || dev_count == 0) {
        vk.destroyInstance(instance, nullptr);
        write_err(err_buf, err_len, "No Vulkan physical devices enumerated (VkResult=%d)", r);
        return FUJI_VK_ERR_NO_DEVICE;
    }
    std::vector<VkPhysicalDevice> phys_devs(dev_count);
    r = vk.enumeratePhysicalDevices(instance, &dev_count, phys_devs.data());
    if (r != VK_SUCCESS) {
        vk.destroyInstance(instance, nullptr);
        write_err(err_buf, err_len, "vkEnumeratePhysicalDevices failed (VkResult=%d)", r);
        return FUJI_VK_ERR_NO_DEVICE;
    }

    int32_t eligible = 0;
    for (uint32_t idx = 0; idx < dev_count; ++idx) {
        VkPhysicalDevice pd = phys_devs[idx];
        int32_t qfam = find_compute_queue_family(vk, pd);
        if (qfam < 0) continue;

        VkPhysicalDeviceProperties props{};
        vk.getPhysicalDeviceProperties(pd, &props);
        if (props.apiVersion < VK_API_VERSION_1_0) continue;

        if (out_devices && eligible < max_devices) {
            FujiVkDeviceInfo info{};
            info.abiVersion = FUJI_VK_OPTICS_ABI_VERSION;
            info.apiVersion = props.apiVersion;
            info.vendorId = props.vendorID;
            info.deviceId = props.deviceID;
            info.deviceType = static_cast<uint32_t>(props.deviceType);
            info.computeQueueFamily = static_cast<uint32_t>(qfam);
            info.externalMemoryCaps = query_external_memory_caps(vk, pd);
            info.deviceIndex = idx;
            std::snprintf(info.deviceName, sizeof(info.deviceName), "%s", props.deviceName);
            std::snprintf(info.loaderName, sizeof(info.loaderName), "%s", loader_name);
            out_devices[eligible] = info;
        }
        eligible++;
    }

    vk.destroyInstance(instance, nullptr);
    if (out_total) *out_total = eligible;
    if (eligible == 0) {
        write_err(err_buf, err_len, "No compute-capable Vulkan physical devices found (scanned=%u)", dev_count);
        return FUJI_VK_ERR_NO_DEVICE;
    }
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT void fuji_vk_set_pipeline_cache_path(const char* cache_path) {
    std::lock_guard<std::mutex> lk(g_cache_path_mutex);
    if (cache_path && cache_path[0] != '\0') {
        g_pipeline_cache_path = cache_path;
    } else {
        g_pipeline_cache_path.clear();
    }
}

FUJI_VK_EXPORT int32_t fuji_vk_create_with_cache(
    int32_t device_index,
    uint32_t select_flags,
    const char* cache_path,
    FujiVkContext** out_ctx,
    FujiVkDeviceInfo* out_info,
    char* err_buf,
    size_t err_buf_len) {
    auto warmupT0 = std::chrono::steady_clock::now();
    if (!out_ctx) {
        write_err(err_buf, err_buf_len, "out_ctx must not be null");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    *out_ctx = nullptr;
    if (select_flags == 0) select_flags = FUJI_VK_SELECT_DEFAULT;

    auto ctx = std::make_unique<FujiVkContext>();
    ctx->info.abiVersion = FUJI_VK_OPTICS_ABI_VERSION;

    int32_t init_rc = open_vulkan_instance(
        ctx->loader, ctx->vk, ctx->instance,
        ctx->info.loaderName, sizeof(ctx->info.loaderName),
        err_buf, err_buf_len);
    if (init_rc != FUJI_VK_OK) {
        return init_rc;
    }

    uint32_t dev_count = 0;
    VkResult r = ctx->vk.enumeratePhysicalDevices(ctx->instance, &dev_count, nullptr);
    if (r != VK_SUCCESS || dev_count == 0) {
        write_err(err_buf, err_buf_len, "No Vulkan physical devices enumerated (VkResult=%d)", r);
        return FUJI_VK_ERR_NO_DEVICE;
    }
    std::vector<VkPhysicalDevice> phys_devs(dev_count);
    r = ctx->vk.enumeratePhysicalDevices(ctx->instance, &dev_count, phys_devs.data());
    if (r != VK_SUCCESS) {
        write_err(err_buf, err_buf_len, "vkEnumeratePhysicalDevices failed (VkResult=%d)", r);
        return FUJI_VK_ERR_NO_DEVICE;
    }

    VkPhysicalDevice best_dev = nullptr;
    uint32_t best_qfam = 0;
    uint32_t best_idx = 0;
    VkPhysicalDeviceProperties best_props{};

    if (device_index >= 0) {
        if (static_cast<uint32_t>(device_index) >= dev_count) {
            write_err(err_buf, err_buf_len, "Requested Vulkan device index %d out of range (found %u devices)", device_index, dev_count);
            return FUJI_VK_ERR_NO_DEVICE;
        }
        VkPhysicalDevice pd = phys_devs[static_cast<uint32_t>(device_index)];
        int32_t chosen_q = find_compute_queue_family(ctx->vk, pd);
        if (chosen_q < 0) {
            VkPhysicalDeviceProperties props{};
            ctx->vk.getPhysicalDeviceProperties(pd, &props);
            write_err(err_buf, err_buf_len, "Requested Vulkan device [%d] '%s' has no compute queue family", device_index, props.deviceName);
            return FUJI_VK_ERR_NO_DEVICE;
        }
        best_dev = pd;
        best_qfam = static_cast<uint32_t>(chosen_q);
        best_idx = static_cast<uint32_t>(device_index);
        ctx->vk.getPhysicalDeviceProperties(pd, &best_props);
    } else {
        int best_score = -1;
        for (uint32_t idx = 0; idx < dev_count; ++idx) {
            VkPhysicalDevice pd = phys_devs[idx];
            VkPhysicalDeviceProperties props{};
            ctx->vk.getPhysicalDeviceProperties(pd, &props);
            int score = score_device_type(props.deviceType, select_flags);
            if (score < 0) continue;

            int32_t chosen_q = find_compute_queue_family(ctx->vk, pd);
            if (chosen_q < 0) continue;

            if (score > best_score) {
                best_score = score;
                best_dev = pd;
                best_qfam = static_cast<uint32_t>(chosen_q);
                best_idx = idx;
                best_props = props;
            }
        }
    }

    if (!best_dev) {
        write_err(err_buf, err_buf_len, "No eligible Vulkan compute GPU found (flags=0x%x, scanned=%u)", select_flags, dev_count);
        return FUJI_VK_ERR_NO_DEVICE;
    }

    ctx->physDev = best_dev;
    ctx->queueFamily = best_qfam;
    ctx->vk.getPhysicalDeviceMemoryProperties(best_dev, &ctx->memProps);

    uint32_t ext_caps = query_external_memory_caps(ctx->vk, best_dev);

    ctx->info.apiVersion = best_props.apiVersion;
    ctx->info.vendorId = best_props.vendorID;
    ctx->info.deviceId = best_props.deviceID;
    ctx->info.deviceType = static_cast<uint32_t>(best_props.deviceType);
    ctx->info.computeQueueFamily = best_qfam;
    ctx->info.externalMemoryCaps = ext_caps;
    ctx->info.deviceIndex = best_idx;
    std::snprintf(ctx->info.deviceName, sizeof(ctx->info.deviceName), "%s", best_props.deviceName);

    float qprio = 1.0f;
    VkDeviceQueueCreateInfo dqci{};
    dqci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    dqci.queueFamilyIndex = best_qfam;
    dqci.queueCount = 1;
    dqci.pQueuePriorities = &qprio;

    VkDeviceCreateInfo dci{};
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &dqci;

    r = ctx->vk.createDevice(best_dev, &dci, nullptr, &ctx->device);
    if (r != VK_SUCCESS) {
        write_err(err_buf, err_buf_len, "vkCreateDevice failed on '%s' (VkResult=%d)", ctx->info.deviceName, r);
        return FUJI_VK_ERR_INIT_FAILED;
    }

    bool dev_ok =
        load_dev_fn(ctx->vk, ctx->device, "vkDestroyDevice", ctx->vk.destroyDevice) &&
        load_dev_fn(ctx->vk, ctx->device, "vkGetDeviceQueue", ctx->vk.getDeviceQueue) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCreateBuffer", ctx->vk.createBuffer) &&
        load_dev_fn(ctx->vk, ctx->device, "vkDestroyBuffer", ctx->vk.destroyBuffer) &&
        load_dev_fn(ctx->vk, ctx->device, "vkGetBufferMemoryRequirements", ctx->vk.getBufferMemoryRequirements) &&
        load_dev_fn(ctx->vk, ctx->device, "vkAllocateMemory", ctx->vk.allocateMemory) &&
        load_dev_fn(ctx->vk, ctx->device, "vkFreeMemory", ctx->vk.freeMemory) &&
        load_dev_fn(ctx->vk, ctx->device, "vkBindBufferMemory", ctx->vk.bindBufferMemory) &&
        load_dev_fn(ctx->vk, ctx->device, "vkMapMemory", ctx->vk.mapMemory) &&
        load_dev_fn(ctx->vk, ctx->device, "vkUnmapMemory", ctx->vk.unmapMemory) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCreateShaderModule", ctx->vk.createShaderModule) &&
        load_dev_fn(ctx->vk, ctx->device, "vkDestroyShaderModule", ctx->vk.destroyShaderModule) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCreatePipelineCache", ctx->vk.createPipelineCache) &&
        load_dev_fn(ctx->vk, ctx->device, "vkDestroyPipelineCache", ctx->vk.destroyPipelineCache) &&
        load_dev_fn(ctx->vk, ctx->device, "vkGetPipelineCacheData", ctx->vk.getPipelineCacheData) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCreateDescriptorSetLayout", ctx->vk.createDescriptorSetLayout) &&
        load_dev_fn(ctx->vk, ctx->device, "vkDestroyDescriptorSetLayout", ctx->vk.destroyDescriptorSetLayout) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCreatePipelineLayout", ctx->vk.createPipelineLayout) &&
        load_dev_fn(ctx->vk, ctx->device, "vkDestroyPipelineLayout", ctx->vk.destroyPipelineLayout) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCreateComputePipelines", ctx->vk.createComputePipelines) &&
        load_dev_fn(ctx->vk, ctx->device, "vkDestroyPipeline", ctx->vk.destroyPipeline) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCreateDescriptorPool", ctx->vk.createDescriptorPool) &&
        load_dev_fn(ctx->vk, ctx->device, "vkDestroyDescriptorPool", ctx->vk.destroyDescriptorPool) &&
        load_dev_fn(ctx->vk, ctx->device, "vkAllocateDescriptorSets", ctx->vk.allocateDescriptorSets) &&
        load_dev_fn(ctx->vk, ctx->device, "vkUpdateDescriptorSets", ctx->vk.updateDescriptorSets) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCreateCommandPool", ctx->vk.createCommandPool) &&
        load_dev_fn(ctx->vk, ctx->device, "vkDestroyCommandPool", ctx->vk.destroyCommandPool) &&
        load_dev_fn(ctx->vk, ctx->device, "vkAllocateCommandBuffers", ctx->vk.allocateCommandBuffers) &&
        load_dev_fn(ctx->vk, ctx->device, "vkResetCommandBuffer", ctx->vk.resetCommandBuffer) &&
        load_dev_fn(ctx->vk, ctx->device, "vkBeginCommandBuffer", ctx->vk.beginCommandBuffer) &&
        load_dev_fn(ctx->vk, ctx->device, "vkEndCommandBuffer", ctx->vk.endCommandBuffer) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCmdBindPipeline", ctx->vk.cmdBindPipeline) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCmdBindDescriptorSets", ctx->vk.cmdBindDescriptorSets) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCmdDispatch", ctx->vk.cmdDispatch) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCmdPipelineBarrier", ctx->vk.cmdPipelineBarrier) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCmdCopyBuffer", ctx->vk.cmdCopyBuffer) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCmdFillBuffer", ctx->vk.cmdFillBuffer) &&
        load_dev_fn(ctx->vk, ctx->device, "vkCreateFence", ctx->vk.createFence) &&
        load_dev_fn(ctx->vk, ctx->device, "vkDestroyFence", ctx->vk.destroyFence) &&
        load_dev_fn(ctx->vk, ctx->device, "vkResetFences", ctx->vk.resetFences) &&
        load_dev_fn(ctx->vk, ctx->device, "vkWaitForFences", ctx->vk.waitForFences) &&
        load_dev_fn(ctx->vk, ctx->device, "vkQueueSubmit", ctx->vk.queueSubmit) &&
        load_dev_fn(ctx->vk, ctx->device, "vkDeviceWaitIdle", ctx->vk.deviceWaitIdle);
    if (!dev_ok) {
        write_err(err_buf, err_buf_len, "Required Vulkan device functions missing");
        return FUJI_VK_ERR_INIT_FAILED;
    }
    /* Optional memory coherency hooks for non-coherent HOST_CACHED readback */
    load_dev_fn(ctx->vk, ctx->device, "vkFlushMappedMemoryRanges", ctx->vk.flushMappedMemoryRanges);
    load_dev_fn(ctx->vk, ctx->device, "vkInvalidateMappedMemoryRanges", ctx->vk.invalidateMappedMemoryRanges);

    ctx->vk.getDeviceQueue(ctx->device, best_qfam, 0, &ctx->computeQueue);

    /* Disk-persistent VkPipelineCache (validated with FVPC header + UUID + SPIR-V SHA-256) */
    ctx->cacheIdentity.abiVersion = FUJI_VK_OPTICS_ABI_VERSION;
    ctx->cacheIdentity.vendorId = best_props.vendorID;
    ctx->cacheIdentity.deviceId = best_props.deviceID;
    ctx->cacheIdentity.driverVersion = best_props.driverVersion;
    std::memcpy(ctx->cacheIdentity.pipelineCacheUUID, best_props.pipelineCacheUUID, VK_UUID_SIZE);
    fuji_vk_compute_spirv_sha256(ctx->cacheIdentity.spirvSha256);
    {
        std::string globalOverride;
        {
            std::lock_guard<std::mutex> lk(g_cache_path_mutex);
            globalOverride = g_pipeline_cache_path;
        }
        ctx->cacheFilePath = fuji_vk_resolve_pipeline_cache_path(
            best_props.vendorID, best_props.deviceID, cache_path, globalOverride);
    }
    std::vector<uint8_t> cachedBlob;
    const bool loadedCache = fuji_vk_load_pipeline_cache_blob(ctx->cacheFilePath, ctx->cacheIdentity, cachedBlob);
    VkPipelineCacheCreateInfo pcci{};
    pcci.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    pcci.initialDataSize = loadedCache ? cachedBlob.size() : 0u;
    pcci.pInitialData = loadedCache ? cachedBlob.data() : nullptr;
    r = ctx->vk.createPipelineCache(ctx->device, &pcci, nullptr, &ctx->pipelineCache);
    if (r != VK_SUCCESS && loadedCache) {
        /* Driver rejected cached blob -> transparently recover with fresh empty VkPipelineCache */
        pcci.initialDataSize = 0u;
        pcci.pInitialData = nullptr;
        r = ctx->vk.createPipelineCache(ctx->device, &pcci, nullptr, &ctx->pipelineCache);
        ctx->pipelineStats.pipelineCacheHit = 0u;
    } else if (r == VK_SUCCESS) {
        ctx->pipelineStats.pipelineCacheHit = loadedCache ? 1u : 0u;
        ctx->pipelineStats.pipelineCacheBytes = loadedCache ? static_cast<uint64_t>(cachedBlob.size()) : 0ULL;
    }
    if (r != VK_SUCCESS) {
        write_err(err_buf, err_buf_len, "vkCreatePipelineCache failed (%d)", r);
        return FUJI_VK_ERR_INIT_FAILED;
    }

    /* Shared descriptor set layout: binding 0=SSBO in, binding 1=SSBO out, binding 2=UBO params */
    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0] = {0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    bindings[1] = {1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    bindings[2] = {2, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};

    VkDescriptorSetLayoutCreateInfo dslci{};
    dslci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslci.bindingCount = 3;
    dslci.pBindings = bindings;
    r = ctx->vk.createDescriptorSetLayout(ctx->device, &dslci, nullptr, &ctx->descSetLayout);
    if (r != VK_SUCCESS) {
        write_err(err_buf, err_buf_len, "vkCreateDescriptorSetLayout failed (%d)", r);
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkPipelineLayoutCreateInfo plci{};
    plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plci.setLayoutCount = 1;
    plci.pSetLayouts = &ctx->descSetLayout;
    r = ctx->vk.createPipelineLayout(ctx->device, &plci, nullptr, &ctx->pipelineLayout);
    if (r != VK_SUCCESS) {
        write_err(err_buf, err_buf_len, "vkCreatePipelineLayout failed (%d)", r);
        return FUJI_VK_ERR_INIT_FAILED;
    }

    const uint32_t totalDescSets = FujiVkContext::kFrameSlots * FujiVkContext::kDescSetsPerSlot;
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0] = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, totalDescSets * 2u};
    poolSizes[1] = {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, totalDescSets};
    VkDescriptorPoolCreateInfo dpci{};
    dpci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpci.maxSets = totalDescSets;
    dpci.poolSizeCount = 2;
    dpci.pPoolSizes = poolSizes;
    r = ctx->vk.createDescriptorPool(ctx->device, &dpci, nullptr, &ctx->descPool);
    if (r != VK_SUCCESS) {
        write_err(err_buf, err_buf_len, "vkCreateDescriptorPool failed (%d)", r);
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkDescriptorSetLayout layouts[FujiVkContext::kFrameSlots * FujiVkContext::kDescSetsPerSlot];
    VkDescriptorSet flatSets[FujiVkContext::kFrameSlots * FujiVkContext::kDescSetsPerSlot];
    for (uint32_t i = 0; i < totalDescSets; ++i) layouts[i] = ctx->descSetLayout;
    VkDescriptorSetAllocateInfo dsai{};
    dsai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsai.descriptorPool = ctx->descPool;
    dsai.descriptorSetCount = totalDescSets;
    dsai.pSetLayouts = layouts;
    r = ctx->vk.allocateDescriptorSets(ctx->device, &dsai, flatSets);
    if (r != VK_SUCCESS) {
        write_err(err_buf, err_buf_len, "vkAllocateDescriptorSets failed (%d)", r);
        return FUJI_VK_ERR_INIT_FAILED;
    }
    for (uint32_t s = 0; s < FujiVkContext::kFrameSlots; ++s) {
        for (uint32_t d = 0; d < FujiVkContext::kDescSetsPerSlot; ++d) {
            ctx->descSets[s][d] = flatSets[s * FujiVkContext::kDescSetsPerSlot + d];
        }
    }

    /* Compile all 6 embedded SPIR-V 1.0 compute shaders + fused ISP stats reduction shader */
    for (uint32_t s = 0; s < FUJI_VK_SHADER_COUNT; ++s) {
        const uint32_t* words = nullptr;
        uint32_t wcount = 0;
        fuji_vk_get_spirv_words(s, &words, &wcount);
        if (!create_compute_pipeline(ctx.get(), words, wcount, ctx->shaderModules[s], ctx->pipelines[s])) {
            write_err(err_buf, err_buf_len, "Shader %u pipeline creation failed: %s", s, ctx->lastError.c_str());
            return FUJI_VK_ERR_INIT_FAILED;
        }
    }
    if (!create_compute_pipeline(
            ctx.get(),
            kFujiSpirvIspStatsReduce,
            static_cast<uint32_t>(sizeof(kFujiSpirvIspStatsReduce) / sizeof(uint32_t)),
            ctx->statsShaderModule,
            ctx->statsPipeline)) {
        write_err(err_buf, err_buf_len, "ISP stats pipeline creation failed: %s", ctx->lastError.c_str());
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkCommandPoolCreateInfo cpci{};
    cpci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cpci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cpci.queueFamilyIndex = best_qfam;
    r = ctx->vk.createCommandPool(ctx->device, &cpci, nullptr, &ctx->cmdPool);
    if (r != VK_SUCCESS) {
        write_err(err_buf, err_buf_len, "vkCreateCommandPool failed (%d)", r);
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkCommandBufferAllocateInfo cbai{};
    cbai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbai.commandPool = ctx->cmdPool;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = FujiVkContext::kFrameSlots;
    r = ctx->vk.allocateCommandBuffers(ctx->device, &cbai, ctx->cmdBufs);
    if (r != VK_SUCCESS) {
        write_err(err_buf, err_buf_len, "vkAllocateCommandBuffers failed (%d)", r);
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkFenceCreateInfo fci{};
    fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    for (uint32_t s = 0; s < FujiVkContext::kFrameSlots; ++s) {
        r = ctx->vk.createFence(ctx->device, &fci, nullptr, &ctx->fences[s]);
        if (r != VK_SUCCESS) {
            write_err(err_buf, err_buf_len, "vkCreateFence failed (%d)", r);
            return FUJI_VK_ERR_INIT_FAILED;
        }
    }

    if (!ctx->ensureHostBuffer(ctx->bufFilterUbo, sizeof(FujiVkFilterStackParams), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT) ||
        !ctx->ensureHostBuffer(ctx->bufOpticsUbo, sizeof(FujiVkOpticsParams), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT) ||
        !ctx->ensureHostBuffer(ctx->bufCmosUbo, sizeof(FujiVkCmosParams), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT) ||
        !ctx->ensureHostBuffer(ctx->bufIspUbo, sizeof(FujiVkIspDemosaicParams), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT) ||
        !ctx->ensureDeviceBuffer(
            ctx->bufIspStatsDevice,
            FUJI_VK_STATS_OUTPUT_BYTES,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT) ||
        !ctx->ensureReadbackBuffer(ctx->bufIspStatsOut, FUJI_VK_STATS_OUTPUT_BYTES, VK_BUFFER_USAGE_TRANSFER_DST_BIT) ||
        !ctx->ensureHostBuffer(ctx->bufIspStatsUbo, sizeof(FujiVkIspStatsParams), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)) {
        write_err(err_buf, err_buf_len, "Uniform buffer allocation failed: %s", ctx->lastError.c_str());
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    /* Pre-warm common LiveView specialized pipelines + default 1024x576 HWM buffers & flush cache */
    // Compile the optional AF-preview kernel before the firmware starts consuming live-view DMA.
    // An unavailable scaler must not prevent other optical pipelines from opening.
    if (create_compute_pipeline(ctx.get(), kSpvUniResz, sizeof(kSpvUniResz) / sizeof(uint32_t),
                                ctx->uniReszModule, ctx->uniReszPipeline)) {
        ctx->ensureHostBuffer(ctx->bufUniReszIn, 575u * 2560u + 2048u, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        ctx->ensureReadbackBuffer(ctx->bufUniReszOut, 640u * 360u * 2u, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        ctx->ensureHostBuffer(ctx->bufUniReszParams, 16, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
    } else {
        ctx->lastError.clear();
    }
    ctx->prewarmDefaultLiveView();
    ctx->flushPipelineCacheToDisk();
    auto warmupT1 = std::chrono::steady_clock::now();
    ctx->pipelineStats.pipelineWarmupMs = std::chrono::duration<double, std::milli>(warmupT1 - warmupT0).count();

    if (out_info) *out_info = ctx->info;
    *out_ctx = ctx.release();
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_create_for_device(
    int32_t device_index,
    uint32_t select_flags,
    FujiVkContext** out_ctx,
    FujiVkDeviceInfo* out_info,
    char* err_buf,
    size_t err_buf_len) {
    return fuji_vk_create_with_cache(device_index, select_flags, nullptr, out_ctx, out_info, err_buf, err_buf_len);
}

FUJI_VK_EXPORT int32_t fuji_vk_create(
    uint32_t select_flags,
    FujiVkContext** out_ctx,
    FujiVkDeviceInfo* out_info,
    char* err_buf,
    size_t err_buf_len) {
    return fuji_vk_create_with_cache(-1, select_flags, nullptr, out_ctx, out_info, err_buf, err_buf_len);
}

extern void fuji_vk_scene3d_destroy_for_context(FujiVkContext* ctx);
extern void fuji_vk_custom_spv_destroy_for_context(FujiVkContext* ctx);
extern bool fuji_vk_custom_spv_has_stage(FujiVkContext* ctx, uint32_t stage);
extern bool fuji_vk_custom_spv_prepare_stage(
    FujiVkContext* ctx,
    uint32_t stage,
    VkBuffer inputBuf,
    uint32_t width,
    uint32_t height,
    VkBuffer* outOutputBuf);
extern void fuji_vk_custom_spv_record_stage(
    FujiVkContext* ctx,
    VkCommandBuffer cmdBuf,
    uint32_t stage,
    VkBuffer copyBackDstIfPost);

FUJI_VK_EXPORT void fuji_vk_destroy(FujiVkContext* ctx) {
    fuji_vk_custom_spv_destroy_for_context(ctx);
    fuji_vk_scene3d_destroy_for_context(ctx);
    delete ctx;
}

FUJI_VK_EXPORT int32_t fuji_vk_get_device_info(
    const FujiVkContext* ctx,
    FujiVkDeviceInfo* out_info) {
    if (!ctx || !out_info) return FUJI_VK_ERR_INVALID_ARG;
    *out_info = ctx->info;
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_get_pipeline_stats(
    const FujiVkContext* ctx,
    FujiVkPipelineStats* out_stats) {
    if (!ctx || !out_stats) return FUJI_VK_ERR_INVALID_ARG;
    *out_stats = ctx->pipelineStats;
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT const char* fuji_vk_last_error(const FujiVkContext* ctx) {
    return ctx ? ctx->lastError.c_str() : "null context";
}

static_assert(sizeof(FujiVkFilterStackParams) == 112, "FujiVkFilterStackParams must be 112 bytes (std140 aligned)");

static void normalize_filter_params(FujiVkFilterStackParams& fp, uint32_t srcWidth, uint32_t srcHeight) {
    if (fp.width == 0) fp.width = srcWidth;
    if (fp.height == 0) fp.height = srcHeight;
    if ((fp.enableFlags & FUJI_VK_FILTER_UV) != 0u) {
        if (fp.uvTintR <= 0.0f && fp.uvTintG <= 0.0f && fp.uvTintB <= 0.0f) {
            fp.uvTintR = 1.0f;
            fp.uvTintG = 1.0f;
            fp.uvTintB = 1.0f;
        }
        if (fp.uvGhostRadiusPx <= 0.0f) {
            float minDim = static_cast<float>(std::min(fp.width, fp.height));
            fp.uvGhostRadiusPx = std::max(2.0f, std::min(16.0f, minDim / 32.0f));
        }
    }
}

static int32_t dispatch_optics_pipeline_internal(
    FujiVkContext* ctx,
    VkBuffer srcBuffer,
    VkDeviceSize srcBytes,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    float* out_rgba,
    uint8_t* out_bayer,
    double* out_gpu_ms) {
    ctx->drainAllAsyncSlots();
    const size_t dstPixels = static_cast<size_t>(optics->dstWidth) * optics->dstHeight;
    const VkDeviceSize dstBytes = dstPixels * 16ULL;
    const bool runFilter = (filter != nullptr) && (filter->enableFlags != 0u);
    const bool runBokeh = ((optics->flags & 2u) != 0u) && (optics->maxBokehPx >= 0.5f);
    const bool runBokehDown = runBokeh && (optics->maxBokehPx > 8.5f) &&
        (optics->dstWidth >= 16u) && (optics->dstHeight >= 16u) &&
        ((optics->dstWidth & 1u) == 0u) && ((optics->dstHeight & 1u) == 0u);
    const size_t halfBaseVec4 = (dstPixels + 15u) & ~static_cast<size_t>(15u);
    const VkDeviceSize halfOffBytes = static_cast<VkDeviceSize>(halfBaseVec4) * 16ULL;
    const VkDeviceSize halfBytes = static_cast<VkDeviceSize>(optics->dstWidth >> 1u) * static_cast<VkDeviceSize>(optics->dstHeight >> 1u) * 16ULL;
    const VkDeviceSize midTotalBytes = runBokehDown ? (halfOffBytes + halfBytes) : dstBytes;
    const bool runCmos = (cmos != nullptr);
    const bool runCustomPre = fuji_vk_custom_spv_has_stage(ctx, 0u);
    const bool runCustomPost = fuji_vk_custom_spv_has_stage(ctx, 1u);

    if (runFilter && !ctx->ensureDeviceBuffer(ctx->bufFilter, srcBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    if (runBokeh && !ctx->ensureDeviceBuffer(ctx->bufMid, midTotalBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    const bool needHostOut = (out_rgba != nullptr);
    const VkFlags outUsage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    if (needHostOut) {
        if (!ctx->ensureReadbackBuffer(ctx->bufOut, dstBytes, outUsage)) {
            return FUJI_VK_ERR_OUT_OF_MEMORY;
        }
    } else {
        if (!ctx->ensureDeviceBuffer(ctx->bufOut, dstBytes, outUsage)) {
            return FUJI_VK_ERR_OUT_OF_MEMORY;
        }
    }
    const VkDeviceSize bayerU32Bytes = dstPixels * 4ULL;
    if (runCmos && !ctx->ensureReadbackBuffer(ctx->bufBayer, bayerU32Bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    const uint32_t slot = ctx->nextSlot();
    VkCommandBuffer cmdBuf = ctx->cmdBufs[slot];
    VkDescriptorSet* ds = ctx->descSets[slot];

    auto t0 = std::chrono::steady_clock::now();
    VkBuffer effectiveSrcBuffer = srcBuffer;
    ctx->cachedOpticsValid = false;
    if (runCustomPre) {
        if (!fuji_vk_custom_spv_prepare_stage(ctx, 0u, srcBuffer, optics->srcWidth, optics->srcHeight, &effectiveSrcBuffer)) {
            return FUJI_VK_ERR_OUT_OF_MEMORY;
        }
    }
    if (runFilter) {
        FujiVkFilterStackParams fp = *filter;
        normalize_filter_params(fp, optics->srcWidth, optics->srcHeight);
        std::memcpy(ctx->bufFilterUbo.mapped, &fp, sizeof(FujiVkFilterStackParams));
        ctx->updateDescriptorSet(ds[3], effectiveSrcBuffer, srcBytes, ctx->bufFilter.buffer, srcBytes, ctx->bufFilterUbo.buffer, sizeof(FujiVkFilterStackParams));
    }

    FujiVkOpticsParams effOptics = *optics;
    if (runBokehDown) {
        effOptics.flags |= 4u;
    }
    std::memcpy(ctx->bufOpticsUbo.mapped, &effOptics, sizeof(FujiVkOpticsParams));
    VkBuffer warpIn = runFilter ? ctx->bufFilter.buffer : effectiveSrcBuffer;
    VkBuffer warpOut = runBokeh ? ctx->bufMid.buffer : ctx->bufOut.buffer;
    ctx->updateDescriptorSet(ds[0], warpIn, srcBytes, warpOut, dstBytes, ctx->bufOpticsUbo.buffer, sizeof(FujiVkOpticsParams));
    if (runBokehDown) {
        ctx->updateDescriptorSetWithOffsets(
            ds[7],
            ctx->bufMid.buffer, 0, dstBytes,
            ctx->bufMid.buffer, halfOffBytes, halfBytes,
            ctx->bufOpticsUbo.buffer, sizeof(FujiVkOpticsParams));
    }
    if (runBokeh) {
        ctx->updateDescriptorSet(ds[1], ctx->bufMid.buffer, midTotalBytes, ctx->bufOut.buffer, dstBytes, ctx->bufOpticsUbo.buffer, sizeof(FujiVkOpticsParams));
    }
    if (runCustomPost) {
        VkBuffer postOutIgnored = nullptr;
        if (!fuji_vk_custom_spv_prepare_stage(ctx, 1u, ctx->bufOut.buffer, optics->dstWidth, optics->dstHeight, &postOutIgnored)) {
            return FUJI_VK_ERR_OUT_OF_MEMORY;
        }
    }
    if (runCmos) {
        std::memcpy(ctx->bufCmosUbo.mapped, cmos, sizeof(FujiVkCmosParams));
        ctx->updateDescriptorSet(ds[2], ctx->bufOut.buffer, dstBytes, ctx->bufBayer.buffer, bayerU32Bytes, ctx->bufCmosUbo.buffer, sizeof(FujiVkCmosParams));
    }

    ctx->vk.resetCommandBuffer(cmdBuf, 0);
    VkCommandBufferBeginInfo cbbi{};
    cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    cbbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (ctx->vk.beginCommandBuffer(cmdBuf, &cbbi) != VK_SUCCESS) {
        ctx->setError("vkBeginCommandBuffer failed");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    VkMemoryBarrier mb{};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

    /* Pass 0a (Optional Plugin SPI v1.0): Custom SPIR-V PRE_OPTICS_FILTER on DEVICE_LOCAL VRAM */
    if (runCustomPre) {
        fuji_vk_custom_spv_record_stage(ctx, cmdBuf, 0u, nullptr);
    }

    /* Pass 0 (Optional, zero-overhead bypass when filter==NULL || enableFlags==0): Optical Filter Stack */
    if (runFilter) {
        const uint32_t fgx = (optics->srcWidth + 7u) / 8u;
        const uint32_t fgy = (optics->srcHeight + 7u) / 8u;
        ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelines[FUJI_VK_SHADER_OPTICAL_FILTER_STACK]);
        ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[3], 0, nullptr);
        ctx->vk.cmdDispatch(cmdBuf, fgx, fgy, 1);
        ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
    }

    const uint32_t gx = (optics->dstWidth + 7u) / 8u;
    const uint32_t gy = (optics->dstHeight + 7u) / 8u;

    /* Pass 1: Optical Warp + CA + Vignetting (zero-branch specialized pipeline) */
    ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->getSpecializedWarpPipeline(optics));
    ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[0], 0, nullptr);
    ctx->vk.cmdDispatch(cmdBuf, gx, gy, 1);

    /* Pass 2: Optional 2D Entrance-Pupil Depth Bokeh (with 2x2 energy-preserving half-res pyramid for cocPx > 8) */
    if (runBokeh) {
        ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        if (runBokehDown) {
            const uint32_t hgx = ((optics->dstWidth >> 1u) + 7u) / 8u;
            const uint32_t hgy = ((optics->dstHeight >> 1u) + 7u) / 8u;
            ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelines[FUJI_VK_SHADER_BOKEH_DOWNSAMPLE]);
            ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[7], 0, nullptr);
            ctx->vk.cmdDispatch(cmdBuf, hgx, hgy, 1);
            ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        }
        ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->getSpecializedBokehPipeline(&effOptics));
        ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[1], 0, nullptr);
        ctx->vk.cmdDispatch(cmdBuf, gx, gy, 1);
    }

    /* Pass 2b (Optional Plugin SPI v1.0): Custom SPIR-V POST_OPTICS_RGB on DEVICE_LOCAL VRAM */
    if (runCustomPost) {
        ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        fuji_vk_custom_spv_record_stage(ctx, cmdBuf, 1u, ctx->bufOut.buffer);
    }

    /* Pass 3: Optional CMOS SplitMix64 Noise + Bayer Mosaic */
    if (runCmos) {
        ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelines[FUJI_VK_SHADER_CMOS_BAYER]);
        ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[2], 0, nullptr);
        ctx->vk.cmdDispatch(cmdBuf, gx, gy, 1);
    }

    if (ctx->vk.endCommandBuffer(cmdBuf) != VK_SUCCESS || !ctx->submitAndWait(slot)) {
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    if (out_rgba) {
        ctx->invalidateReadback(ctx->bufOut);
        std::memcpy(out_rgba, ctx->bufOut.mapped, dstBytes);
    }
    if (runCmos && out_bayer) {
        ctx->invalidateReadback(ctx->bufBayer);
        pack_bayer_u32_to_bytes(reinterpret_cast<const uint32_t*>(ctx->bufBayer.mapped), out_bayer, dstPixels, cmos->bitDepth);
    }
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) {
        *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    }
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_render_optics_filtered(
    FujiVkContext* ctx,
    const float* in_rgba_depth,
    float* out_rgba,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* params,
    double* out_gpu_ms) {
    if (!ctx || !in_rgba_depth || !out_rgba || !params) return FUJI_VK_ERR_INVALID_ARG;
    if (params->srcWidth == 0 || params->srcHeight == 0 || params->dstWidth == 0 || params->dstHeight == 0) {
        ctx->setError("Invalid optics dimensions");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const size_t srcPixels = static_cast<size_t>(params->srcWidth) * params->srcHeight;
    const VkDeviceSize inBytes = srcPixels * 16ULL;
    if (!ctx->ensureHostBuffer(ctx->bufIn, inBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    std::memcpy(ctx->bufIn.mapped, in_rgba_depth, inBytes);
    return dispatch_optics_pipeline_internal(ctx, ctx->bufIn.buffer, inBytes, filter, params, nullptr, out_rgba, nullptr, out_gpu_ms);
}

FUJI_VK_EXPORT int32_t fuji_vk_render_optics(
    FujiVkContext* ctx,
    const float* in_rgba_depth,
    float* out_rgba,
    const FujiVkOpticsParams* params,
    double* out_gpu_ms) {
    return fuji_vk_render_optics_filtered(ctx, in_rgba_depth, out_rgba, nullptr, params, out_gpu_ms);
}

FUJI_VK_EXPORT int32_t fuji_vk_render_cmos_bayer(
    FujiVkContext* ctx,
    const float* in_rgba,
    uint8_t* out_bayer,
    size_t out_bayer_bytes,
    const FujiVkCmosParams* params,
    double* out_gpu_ms) {
    if (!ctx || !in_rgba || !out_bayer || !params) return FUJI_VK_ERR_INVALID_ARG;
    if (params->width == 0 || params->height == 0 || params->bitDepth < 8 || params->bitDepth > 16) {
        ctx->setError("Invalid CMOS parameters");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const size_t pixels = static_cast<size_t>(params->width) * params->height;
    const size_t bytesPerPx = (params->bitDepth <= 8) ? 1u : 2u;
    if (out_bayer_bytes < pixels * bytesPerPx) {
        ctx->setError("out_bayer_bytes too small");
        return FUJI_VK_ERR_INVALID_ARG;
    }

    const VkDeviceSize inBytes = pixels * 16ULL;
    const VkDeviceSize bayerU32Bytes = pixels * 4ULL;
    if (!ctx->ensureHostBuffer(ctx->bufIn, inBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) ||
        !ctx->ensureHostBuffer(ctx->bufBayer, bayerU32Bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    const uint32_t slot = ctx->nextSlot();
    VkCommandBuffer cmdBuf = ctx->cmdBufs[slot];
    VkDescriptorSet* ds = ctx->descSets[slot];

    auto t0 = std::chrono::steady_clock::now();
    std::memcpy(ctx->bufIn.mapped, in_rgba, inBytes);
    std::memcpy(ctx->bufCmosUbo.mapped, params, sizeof(FujiVkCmosParams));
    ctx->updateDescriptorSet(ds[2], ctx->bufIn.buffer, inBytes, ctx->bufBayer.buffer, bayerU32Bytes, ctx->bufCmosUbo.buffer, sizeof(FujiVkCmosParams));

    ctx->vk.resetCommandBuffer(cmdBuf, 0);
    VkCommandBufferBeginInfo cbbi{};
    cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    cbbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (ctx->vk.beginCommandBuffer(cmdBuf, &cbbi) != VK_SUCCESS) {
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelines[FUJI_VK_SHADER_CMOS_BAYER]);
    ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[2], 0, nullptr);
    ctx->vk.cmdDispatch(cmdBuf, (params->width + 7u) / 8u, (params->height + 7u) / 8u, 1);

    if (ctx->vk.endCommandBuffer(cmdBuf) != VK_SUCCESS || !ctx->submitAndWait(slot)) {
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    pack_bayer_u32_to_bytes(reinterpret_cast<const uint32_t*>(ctx->bufBayer.mapped), out_bayer, pixels, params->bitDepth);
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) {
        *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    }
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_render_optics_to_bayer_filtered(
    FujiVkContext* ctx,
    const float* in_rgba_depth,
    uint8_t* out_bayer,
    size_t out_bayer_bytes,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    double* out_gpu_ms) {
    if (!ctx || !in_rgba_depth || !out_bayer || !optics || !cmos) return FUJI_VK_ERR_INVALID_ARG;
    if (optics->dstWidth != cmos->width || optics->dstHeight != cmos->height || optics->srcWidth == 0 || optics->srcHeight == 0) {
        ctx->setError("Optics dst dimensions must match CMOS dimensions");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    if (cmos->bitDepth < 8 || cmos->bitDepth > 16) {
        ctx->setError("Invalid CMOS bitDepth");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const size_t srcPixels = static_cast<size_t>(optics->srcWidth) * optics->srcHeight;
    const size_t dstPixels = static_cast<size_t>(optics->dstWidth) * optics->dstHeight;
    const size_t bytesPerPx = (cmos->bitDepth <= 8) ? 1u : 2u;
    if (out_bayer_bytes < dstPixels * bytesPerPx) {
        ctx->setError("out_bayer_bytes too small");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const VkDeviceSize inBytes = srcPixels * 16ULL;
    if (!ctx->ensureHostBuffer(ctx->bufIn, inBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    std::memcpy(ctx->bufIn.mapped, in_rgba_depth, inBytes);
    return dispatch_optics_pipeline_internal(ctx, ctx->bufIn.buffer, inBytes, filter, optics, cmos, nullptr, out_bayer, out_gpu_ms);
}

FUJI_VK_EXPORT int32_t fuji_vk_render_optics_to_bayer(
    FujiVkContext* ctx,
    const float* in_rgba_depth,
    uint8_t* out_bayer,
    size_t out_bayer_bytes,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    double* out_gpu_ms) {
    return fuji_vk_render_optics_to_bayer_filtered(ctx, in_rgba_depth, out_bayer, out_bayer_bytes, nullptr, optics, cmos, out_gpu_ms);
}

static_assert(sizeof(FujiVkIspDemosaicParams) == 160, "FujiVkIspDemosaicParams must be 160 bytes (std140 aligned)");

static const uint32_t* default_srgb_lut_packed() {
    static const auto table = [] {
        std::array<uint32_t, 4096> entries{};
        for (int i = 0; i < 4096; ++i) {
            double c = static_cast<double>(i) / 4095.0;
            double srgb = (c <= 0.0031308) ? (c * 12.92) : (1.055 * std::pow(c, 1.0 / 2.4) - 0.055);
            int v = static_cast<int>(std::llround(srgb * 255.0));
            if (v < 0) v = 0;
            if (v > 255) v = 255;
            uint32_t u = static_cast<uint32_t>(v);
            entries[i] = u | (u << 8) | (u << 16);
        }
        return entries;
    }();
    return table.data();
}

static bool upload_isp_lut_cached(
    FujiVkContext* ctx, uint32_t* dstWords, const int32_t* red, const int32_t* green, const int32_t* blue) {
    const bool useDefault = !red || !green || !blue;
    if (useDefault) {
        if (ctx->cachedLutValid && ctx->cachedLutDefault) return false;
        std::memcpy(dstWords, default_srgb_lut_packed(), sizeof(ctx->cachedLutPacked));
    } else {
        auto packed = [&](size_t i) {
            return (static_cast<uint32_t>(red[i]) & 255u) |
                   ((static_cast<uint32_t>(green[i]) & 255u) << 8) |
                   ((static_cast<uint32_t>(blue[i]) & 255u) << 16);
        };
        if (ctx->cachedLutValid && !ctx->cachedLutDefault) {
            uint32_t difference = 0;
            for (size_t i = 0; i < 4096; ++i) difference |= ctx->cachedLutPacked[i] ^ packed(i);
            if (!difference) return false;
        }
        for (size_t i = 0; i < 4096; ++i) ctx->cachedLutPacked[i] = packed(i);
        std::memcpy(dstWords, ctx->cachedLutPacked, sizeof(ctx->cachedLutPacked));
    }
    ctx->cachedLutDefault = useDefault;
    ctx->cachedLutValid = true;
    ctx->cachedLutDeviceSynced = false;
    return true;
}

static void normalize_isp_params(FujiVkIspDemosaicParams& p) {
    if ((p.flags & FUJI_VK_ISP_FLAG_SRC_CROP) != 0u) {
        if (p.fitW == 0 || p.fitH == 0) {
            p.offX = 0; p.offY = 0; p.fitW = p.srcWidth; p.fitH = p.srcHeight;
        } else {
            if (p.offX >= p.srcWidth) p.offX = p.srcWidth - 1u;
            if (p.offY >= p.srcHeight) p.offY = p.srcHeight - 1u;
            uint32_t maxW = p.srcWidth - p.offX;
            uint32_t maxH = p.srcHeight - p.offY;
            if (p.fitW > maxW) p.fitW = maxW;
            if (p.fitH > maxH) p.fitH = maxH;
            if (p.fitW == 0) p.fitW = 1u;
            if (p.fitH == 0) p.fitH = 1u;
        }
    } else if (p.fitW == 0 || p.fitH == 0) {
        if (p.srcWidth == p.dstWidth && p.srcHeight == p.dstHeight) {
            p.offX = 0; p.offY = 0; p.fitW = p.dstWidth; p.fitH = p.dstHeight;
        } else {
            double sc = std::min(static_cast<double>(p.dstWidth) / p.srcWidth, static_cast<double>(p.dstHeight) / p.srcHeight);
            uint32_t fw = std::max<uint32_t>(1u, std::min<uint32_t>(p.dstWidth, static_cast<uint32_t>(std::llround(p.srcWidth * sc))));
            uint32_t fh = std::max<uint32_t>(1u, std::min<uint32_t>(p.dstHeight, static_cast<uint32_t>(std::llround(p.srcHeight * sc))));
            p.fitW = fw; p.fitH = fh;
            p.offX = (p.dstWidth - fw) / 2u;
            p.offY = (p.dstHeight - fh) / 2u;
        }
    }
    if (p.gainR1024 == 0 && p.gainG1024 == 0 && p.gainB1024 == 0) {
        p.gainR1024 = 1024u; p.gainG1024 = 1024u; p.gainB1024 = 1024u;
    }
    if (p.yuvShift == 0) p.yuvShift = 10u;
}

static void pack_isp_pairs_to_yuv(
    const uint32_t* pairs,
    const FujiVkIspDemosaicParams& p,
    uint8_t* out_y_or_packed,
    uint8_t* out_uv_plane,
    uint32_t stride,
    uint32_t rowBytes,
    size_t pairsW) {
    const uint32_t fmt = p.outputFormat;
    const bool isNv12 = (fmt == FUJI_VK_ISP_FMT_NV12);
    const bool isYyuv422 = (fmt == FUJI_VK_ISP_FMT_YYUV422);
    if (fmt == FUJI_VK_ISP_FMT_UYVY422) {
        if (stride == rowBytes) {
            std::memcpy(out_y_or_packed, pairs, static_cast<size_t>(p.dstHeight) * rowBytes);
        } else {
            for (uint32_t y = 0; y < p.dstHeight; ++y) {
                std::memcpy(out_y_or_packed + static_cast<size_t>(y) * stride, pairs + static_cast<size_t>(y) * pairsW, rowBytes);
            }
        }
    } else if (isYyuv422) {
        for (uint32_t y = 0; y < p.dstHeight; ++y) {
            uint8_t* rowDst = out_y_or_packed + static_cast<size_t>(y) * stride;
            const uint32_t* rowSrc = pairs + static_cast<size_t>(y) * pairsW;
            for (size_t x = 0; x < pairsW; ++x) {
                uint32_t w = rowSrc[x];
                rowDst[x * 4 + 0] = static_cast<uint8_t>((w >> 8) & 0xFFu);  /* Y0 */
                rowDst[x * 4 + 1] = static_cast<uint8_t>((w >> 24) & 0xFFu); /* Y1 */
                rowDst[x * 4 + 2] = static_cast<uint8_t>(w & 0xFFu);         /* U0 */
                rowDst[x * 4 + 3] = static_cast<uint8_t>((w >> 16) & 0xFFu); /* V0 */
            }
        }
    } else {
        /* NV16 or NV12 semi-planar */
        for (uint32_t y = 0; y < p.dstHeight; ++y) {
            uint8_t* yDst = out_y_or_packed + static_cast<size_t>(y) * stride;
            const uint32_t* rowSrc = pairs + static_cast<size_t>(y) * pairsW;
            const bool writeUv = !isNv12 || ((y & 1u) == 0u);
            uint8_t* uvDst = writeUv ? (out_uv_plane + static_cast<size_t>(isNv12 ? (y >> 1) : y) * stride) : nullptr;
            for (size_t x = 0; x < pairsW; ++x) {
                uint32_t w = rowSrc[x];
                yDst[x * 2 + 0] = static_cast<uint8_t>((w >> 8) & 0xFFu);
                yDst[x * 2 + 1] = static_cast<uint8_t>((w >> 24) & 0xFFu);
                if (uvDst) {
                    uvDst[x * 2 + 0] = static_cast<uint8_t>(w & 0xFFu);
                    uvDst[x * 2 + 1] = static_cast<uint8_t>((w >> 16) & 0xFFu);
                }
            }
        }
    }
}

FUJI_VK_EXPORT int32_t fuji_vk_isp_demosaic(
    FujiVkContext* ctx,
    const uint8_t* raw_bayer,
    size_t raw_bayer_bytes,
    const int32_t* lut_r_4096,
    const int32_t* lut_g_4096,
    const int32_t* lut_b_4096,
    uint8_t* out_y_or_packed,
    size_t out_y_or_packed_bytes,
    uint8_t* out_uv_plane,
    size_t out_uv_plane_bytes,
    const FujiVkIspDemosaicParams* params,
    double* out_gpu_ms) {
    if (!ctx || !raw_bayer || !out_y_or_packed || !params) return FUJI_VK_ERR_INVALID_ARG;
    if (params->srcWidth < 2 || params->srcHeight < 2 || params->dstWidth < 2 || (params->dstWidth & 1u) != 0u || params->dstHeight < 2) {
        ctx->setError("Invalid ISP demosaic dimensions (dstWidth must be even >= 2)");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const size_t srcPixels = static_cast<size_t>(params->srcWidth) * params->srcHeight;
    const size_t rawReq = params->is16Bit ? (srcPixels * 2u) : srcPixels;
    if (raw_bayer_bytes < rawReq) {
        ctx->setError("raw_bayer_bytes smaller than srcWidth*srcHeight*(is16Bit?2:1)");
        return FUJI_VK_ERR_INVALID_ARG;
    }

    const uint32_t fmt = params->outputFormat;
    const bool isSemiPlanar = (fmt == FUJI_VK_ISP_FMT_NV16 || fmt == FUJI_VK_ISP_FMT_NV12);
    const bool isNv12 = (fmt == FUJI_VK_ISP_FMT_NV12);
    if (fmt > FUJI_VK_ISP_FMT_YYUV422) {
        ctx->setError("Unsupported ISP outputFormat");
        return FUJI_VK_ERR_INVALID_ARG;
    }

    const uint32_t rowBytes = isSemiPlanar ? params->dstWidth : (params->dstWidth * 2u);
    const uint32_t stride = (params->dstStrideBytes >= rowBytes) ? params->dstStrideBytes : rowBytes;
    const size_t yReq = static_cast<size_t>(params->dstHeight - 1u) * stride + rowBytes;
    if (out_y_or_packed_bytes < yReq) {
        ctx->setError("out_y_or_packed_bytes too small for dstHeight*stride");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const uint32_t uvH = isNv12 ? ((params->dstHeight + 1u) / 2u) : params->dstHeight;
    if (isSemiPlanar) {
        const size_t uvReq = static_cast<size_t>(uvH - 1u) * stride + rowBytes;
        if (!out_uv_plane || out_uv_plane_bytes < uvReq) {
            ctx->setError("out_uv_plane missing or too small for semi-planar NV16/NV12");
            return FUJI_VK_ERR_INVALID_ARG;
        }
    }

    FujiVkIspDemosaicParams p = *params;
    normalize_isp_params(p);

    const bool runPeaking = ((p.flags & FUJI_VK_ISP_FLAG_PEAKING_ENABLED) != 0u) && (p.dstWidth >= 6u) && (p.dstHeight >= 5u);
    const size_t pairsW = p.dstWidth / 2u;
    const size_t pairCount = pairsW * p.dstHeight;
    const VkDeviceSize inBytes = (4096ULL + srcPixels) * 4ULL;
    const VkDeviceSize outPairsBytes = pairCount * 4ULL;

    ctx->drainAllAsyncSlots();
    if (!ctx->ensureHostBuffer(ctx->bufIspIn, inBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT) ||
        !ctx->ensureDeviceBuffer(ctx->bufIspInDevice, inBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT) ||
        !ctx->ensureReadbackBuffer(ctx->bufIspOut, outPairsBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) ||
        (runPeaking && !ctx->ensureDeviceBuffer(ctx->bufIspMid, outPairsBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT))) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    const uint32_t slot = ctx->nextSlot();
    VkCommandBuffer cmdBuf = ctx->cmdBufs[slot];
    VkDescriptorSet* ds = ctx->descSets[slot];

    auto t0 = std::chrono::steady_clock::now();
    ctx->cachedIspBayerValid = false;
    uint32_t* inDst = reinterpret_cast<uint32_t*>(ctx->bufIspIn.mapped);
    upload_isp_lut_cached(ctx, inDst, lut_r_4096, lut_g_4096, lut_b_4096);

    uint32_t* bayerDst = inDst + 4096;
    if (p.is16Bit) {
        for (size_t i = 0; i < srcPixels; ++i) {
            uint32_t v = static_cast<uint32_t>(raw_bayer[i * 2]) | (static_cast<uint32_t>(raw_bayer[i * 2 + 1]) << 8);
            bayerDst[i] = v >> 4;
        }
    } else {
        for (size_t i = 0; i < srcPixels; ++i) {
            bayerDst[i] = (static_cast<uint32_t>(raw_bayer[i]) * 4095u) / 255u;
        }
    }

    std::memcpy(ctx->bufIspUbo.mapped, &p, sizeof(FujiVkIspDemosaicParams));
    VkBuffer demosaicOut = runPeaking ? ctx->bufIspMid.buffer : ctx->bufIspOut.buffer;
    ctx->updateDescriptorSet(ds[4], ctx->bufIspInDevice.buffer, inBytes, demosaicOut, outPairsBytes, ctx->bufIspUbo.buffer, sizeof(FujiVkIspDemosaicParams));
    if (runPeaking) {
        ctx->updateDescriptorSet(ds[5], ctx->bufIspMid.buffer, outPairsBytes, ctx->bufIspOut.buffer, outPairsBytes, ctx->bufIspUbo.buffer, sizeof(FujiVkIspDemosaicParams));
    }

    ctx->vk.resetCommandBuffer(cmdBuf, 0);
    VkCommandBufferBeginInfo cbbi{};
    cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    cbbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (ctx->vk.beginCommandBuffer(cmdBuf, &cbbi) != VK_SUCCESS) {
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    /* Copy staged LUT + Bayer into DEVICE_LOCAL VRAM so 3x3 demosaic reads stay in VRAM L2 (zero PCIe thrash) */
    VkBufferCopy uploadCopy{};
    uploadCopy.srcOffset = 0;
    uploadCopy.dstOffset = 0;
    uploadCopy.size = inBytes;
    ctx->vk.cmdCopyBuffer(cmdBuf, ctx->bufIspIn.buffer, ctx->bufIspInDevice.buffer, 1, &uploadCopy);
    VkMemoryBarrier xferToComp{};
    xferToComp.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    xferToComp.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    xferToComp.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &xferToComp, 0, nullptr, 0, nullptr);

    const uint32_t gx = static_cast<uint32_t>((pairsW + 7u) / 8u);
    const uint32_t gy = (p.dstHeight + 7u) / 8u;

    ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->getSpecializedIspDemosaicPipeline(p));
    ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[4], 0, nullptr);
    ctx->vk.cmdDispatch(cmdBuf, gx, gy, 1);

    if (runPeaking) {
        VkMemoryBarrier mb{};
        mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);

        ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelines[FUJI_VK_SHADER_ISP_PEAKING]);
        ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[5], 0, nullptr);
        ctx->vk.cmdDispatch(cmdBuf, gx, gy, 1);
    }

    if (ctx->vk.endCommandBuffer(cmdBuf) != VK_SUCCESS || !ctx->submitAndWait(slot)) {
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }
    ctx->cachedLutDeviceSynced = true;

    ctx->invalidateReadback(ctx->bufIspOut);
    const uint32_t* pairs = reinterpret_cast<const uint32_t*>(ctx->bufIspOut.mapped);
    pack_isp_pairs_to_yuv(pairs, p, out_y_or_packed, out_uv_plane, stride, rowBytes, pairsW);

    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) {
        *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    }
    return FUJI_VK_OK;
}

static void normalize_fused_stats_params(
    const FujiVkIspStatsParams& in,
    uint32_t cmosW,
    uint32_t cmosH,
    FujiVkIspStatsParams& out) {
    out = in;
    out.width = cmosW;
    out.height = cmosH;
    out.inputFormat = FUJI_VK_STATS_FMT_BAYER_RGGB_U32_12;
    if (out.flags == 0u) out.flags = FUJI_VK_STATS_FLAG_ALL;
    if (out.viewX >= cmosW) out.viewX = 0u;
    if (out.viewY >= cmosH) out.viewY = 0u;
    out.viewX &= ~1u;
    out.viewY &= ~1u;
    if (out.viewW == 0u || out.viewX + out.viewW > cmosW) out.viewW = cmosW - out.viewX;
    if (out.viewH == 0u || out.viewY + out.viewH > cmosH) out.viewH = cmosH - out.viewY;
    out.viewW &= ~1u;
    out.viewH &= ~1u;
    if (out.viewW < 2u) { out.viewX = 0u; out.viewW = cmosW & ~1u; }
    if (out.viewH < 2u) { out.viewY = 0u; out.viewH = cmosH & ~1u; }
    if (out.gridCols == 0u || out.gridCols > 16u) out.gridCols = 16u;
    if (out.gridRows == 0u || out.gridRows > 16u) out.gridRows = 16u;
    if (out.stepQuad == 0u) {
        out.stepQuad = (out.viewW >= 512u && out.viewH >= 256u) ? 2u : 1u;
    }
    if (out.awbHighlightThresh10 == 0u) out.awbHighlightThresh10 = 960u;
    if (out.afRoiX1 == 0u || out.afRoiX1 > cmosW) out.afRoiX1 = out.viewX + out.viewW;
    if (out.afRoiY1 == 0u || out.afRoiY1 > cmosH) out.afRoiY1 = out.viewY + out.viewH;
    if (out.afRoiX0 >= out.afRoiX1) out.afRoiX0 = out.viewX;
    if (out.afRoiY0 >= out.afRoiY1) out.afRoiY0 = out.viewY;
    if (out.afCols == 0u || out.afCols > 13u) out.afCols = 13u;
    if (out.afRows == 0u || out.afRows > 9u) out.afRows = 9u;
    if (out.afStepQuad == 0u) out.afStepQuad = out.stepQuad;
    if (out.ob14 == 0u) out.ob14 = 1024u;
}

static uint64_t compute_optics_stage1_key(
    uint64_t sceneKey,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    bool runCustomPre,
    bool runCustomPost) {
    if (sceneKey == 0ULL || !optics || runCustomPre || runCustomPost) {
        return 0ULL;
    }
    uint64_t h = 14695981039346656037ULL;
    auto mixBytes = [&](const void* ptr, size_t n) {
        const uint8_t* p = reinterpret_cast<const uint8_t*>(ptr);
        for (size_t i = 0; i < n; ++i) {
            h ^= static_cast<uint64_t>(p[i]);
            h *= 1099511628211ULL;
        }
    };
    mixBytes(&sceneKey, sizeof(sceneKey));
    mixBytes(optics, sizeof(FujiVkOpticsParams));
    const uint32_t hasFilter = (filter != nullptr && filter->enableFlags != 0u) ? 1u : 0u;
    mixBytes(&hasFilter, sizeof(hasFilter));
    if (hasFilter) {
        FujiVkFilterStackParams fp = *filter;
        normalize_filter_params(fp, optics->srcWidth, optics->srcHeight);
        mixBytes(&fp, sizeof(FujiVkFilterStackParams));
    }
    return (h == 0ULL) ? 1ULL : h;
}

static int32_t dispatch_optics_to_isp_yuv_internal(
    FujiVkContext* ctx,
    VkBuffer srcBuffer,
    VkDeviceSize inBytes,
    uint64_t sceneKeyForCache,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    const int32_t* lut_r_4096,
    const int32_t* lut_g_4096,
    const int32_t* lut_b_4096,
    uint8_t* out_y_or_packed,
    size_t out_y_or_packed_bytes,
    uint8_t* out_uv_plane,
    size_t out_uv_plane_bytes,
    const FujiVkIspDemosaicParams* isp_params,
    const FujiVkIspStatsParams* stats_params,
    FujiVkIspStatsOutput* out_stats,
    double* out_gpu_ms,
    const uint8_t** mapped_uyvy = nullptr) {
    if (!ctx || !srcBuffer || !optics || !cmos || (!out_y_or_packed && !mapped_uyvy) || !isp_params) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    if (optics->srcWidth == 0 || optics->srcHeight == 0 ||
        optics->dstWidth != cmos->width || optics->dstHeight != cmos->height ||
        isp_params->srcWidth != cmos->width || isp_params->srcHeight != cmos->height) {
        ctx->setError("Fused Optics->ISP dimensions mismatch (optics.dst == cmos == isp.src required)");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    if (cmos->bitDepth < 8 || cmos->bitDepth > 16 ||
        isp_params->dstWidth < 2 || isp_params->dstWidth > UINT32_MAX / 2u ||
        (isp_params->dstWidth & 1u) != 0u || isp_params->dstHeight < 2) {
        ctx->setError("Invalid CMOS bitDepth or ISP output dimensions");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const uint32_t fmt = isp_params->outputFormat;
    const bool isSemiPlanar = (fmt == FUJI_VK_ISP_FMT_NV16 || fmt == FUJI_VK_ISP_FMT_NV12);
    const bool isNv12 = (fmt == FUJI_VK_ISP_FMT_NV12);
    if (fmt > FUJI_VK_ISP_FMT_YYUV422) {
        ctx->setError("Unsupported ISP outputFormat");
        return FUJI_VK_ERR_INVALID_ARG;
    }

    const uint32_t rowBytes = isSemiPlanar ? isp_params->dstWidth : (isp_params->dstWidth * 2u);
    const uint32_t stride = (isp_params->dstStrideBytes >= rowBytes) ? isp_params->dstStrideBytes : rowBytes;
    const size_t yReq = static_cast<size_t>(isp_params->dstHeight - 1u) * stride + rowBytes;
    if (out_y_or_packed_bytes < yReq) {
        ctx->setError("out_y_or_packed_bytes too small for fused ISP output");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const uint32_t uvH = isNv12 ? ((isp_params->dstHeight + 1u) / 2u) : isp_params->dstHeight;
    if (isSemiPlanar) {
        const size_t uvReq = static_cast<size_t>(uvH - 1u) * stride + rowBytes;
        if (!out_uv_plane || out_uv_plane_bytes < uvReq) {
            ctx->setError("out_uv_plane missing or too small for fused semi-planar NV16/NV12");
            return FUJI_VK_ERR_INVALID_ARG;
        }
    }

    FujiVkIspDemosaicParams p = *isp_params;
    normalize_isp_params(p);

    const size_t cmosPixels = static_cast<size_t>(optics->dstWidth) * optics->dstHeight;
    const VkDeviceSize dstRgbBytes = cmosPixels * 16ULL;
    const VkDeviceSize bayerU32Bytes = cmosPixels * 4ULL;
    const VkDeviceSize lutBytes = 4096ULL * 4ULL; /* 16384 B: 256-B aligned for SSBO offset binding */
    const VkDeviceSize ispInBytes = lutBytes + bayerU32Bytes;

    const bool runFilter = (filter != nullptr) && (filter->enableFlags != 0u);
    const bool runBokeh = ((optics->flags & 2u) != 0u) && (optics->maxBokehPx >= 0.5f);
    const bool runPeaking = ((p.flags & FUJI_VK_ISP_FLAG_PEAKING_ENABLED) != 0u) && (p.dstWidth >= 6u) && (p.dstHeight >= 5u);
    const bool runCustomPre = fuji_vk_custom_spv_has_stage(ctx, 0u);
    const bool runCustomPost = fuji_vk_custom_spv_has_stage(ctx, 1u);
    const bool runStats = (stats_params != nullptr) && (out_stats != nullptr) && (ctx->statsPipeline != nullptr);
    FujiVkIspStatsParams sp{};
    if (runStats) {
        normalize_fused_stats_params(*stats_params, cmos->width, cmos->height, sp);
    }

    const size_t pairsW = p.dstWidth / 2u;
    const size_t pairCount = pairsW * p.dstHeight;
    const VkDeviceSize outPairsBytes = pairCount * 4ULL;

    const bool runBokehDown = runBokeh && (optics->maxBokehPx > 8.5f) &&
        (optics->dstWidth >= 16u) && (optics->dstHeight >= 16u) &&
        ((optics->dstWidth & 1u) == 0u) && ((optics->dstHeight & 1u) == 0u);
    const size_t halfBaseVec4 = (cmosPixels + 15u) & ~static_cast<size_t>(15u);
    const VkDeviceSize halfOffBytes = static_cast<VkDeviceSize>(halfBaseVec4) * 16ULL;
    const VkDeviceSize halfBytes = static_cast<VkDeviceSize>(optics->dstWidth >> 1u) * static_cast<VkDeviceSize>(optics->dstHeight >> 1u) * 16ULL;
    const VkDeviceSize midTotalBytes = runBokehDown ? (halfOffBytes + halfBytes) : dstRgbBytes;

    ctx->drainAllAsyncSlots();
    const uint64_t opticsStage1Key = compute_optics_stage1_key(sceneKeyForCache, filter, optics, runCustomPre, runCustomPost);
    const VkBuffer prevOutBuf = ctx->bufOut.buffer;
    const bool candidateStage1Hit = (opticsStage1Key != 0ULL) &&
        ctx->cachedOpticsValid &&
        (ctx->cachedOpticsKey == opticsStage1Key) &&
        (ctx->cachedOpticsBytes == dstRgbBytes) &&
        (prevOutBuf != nullptr);

    const VkFlags outUsage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    if ((!candidateStage1Hit && runFilter && !ctx->ensureDeviceBuffer(ctx->bufFilter, inBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) ||
        (!candidateStage1Hit && runBokeh && !ctx->ensureDeviceBuffer(ctx->bufMid, midTotalBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) ||
        !ctx->ensureDeviceBuffer(ctx->bufOut, dstRgbBytes, outUsage) ||
        !ctx->ensureHostBuffer(ctx->bufIspIn, lutBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT) ||
        !ctx->ensureDeviceBuffer(ctx->bufIspInDevice, ispInBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT) ||
        (runPeaking && !ctx->ensureDeviceBuffer(ctx->bufIspMid, outPairsBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) ||
        !ctx->ensureReadbackBuffer(ctx->bufIspOut, outPairsBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    const bool stage1VramHit = candidateStage1Hit && (ctx->bufOut.buffer == prevOutBuf);
    if (!stage1VramHit && candidateStage1Hit) {
        if ((runFilter && !ctx->ensureDeviceBuffer(ctx->bufFilter, inBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) ||
            (runBokeh && !ctx->ensureDeviceBuffer(ctx->bufMid, midTotalBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT))) {
            return FUJI_VK_ERR_OUT_OF_MEMORY;
        }
    }

    const uint32_t slot = ctx->nextSlot();
    VkCommandBuffer cmdBuf = ctx->cmdBufs[slot];
    VkDescriptorSet* ds = ctx->descSets[slot];

    auto t0 = std::chrono::steady_clock::now();

    /* Stage 4096-entry ISP tone LUT in 16 KiB host staging and copy to DEVICE_LOCAL bufIspInDevice[0..4095] only on change */
    upload_isp_lut_cached(ctx, reinterpret_cast<uint32_t*>(ctx->bufIspIn.mapped), lut_r_4096, lut_g_4096, lut_b_4096);
    const bool needLutCopyToDevice = !ctx->cachedLutDeviceSynced;

    /* The fused CMOS pass packs the quantized ADC result exactly as the
     * standalone host Bayer upload does. Values 0 and 1 in the internal UBO
     * select 8-bit expansion and 16-bit truncation; ordinary Bayer dispatches
     * retain the public bitDepth (8..16) and emit the native ADC code. */
    FujiVkCmosParams fusedCmos = *cmos;
    fusedCmos.bitDepth = p.is16Bit ? 1u : 0u;
    // Frame-index variation affects the shader only when shot/read noise is enabled.
    if (!fusedCmos.enableNoise) fusedCmos.frameIndexLo = 0;
    const bool reuseBayer = stage1VramHit && ctx->cachedIspBayerValid && std::memcmp(&ctx->cachedIspBayerParams, &fusedCmos, sizeof(fusedCmos)) == 0;
    if (!reuseBayer) ctx->cachedIspBayerValid = false;

    FujiVkOpticsParams effOptics = *optics;
    if (runBokehDown) {
        effOptics.flags |= 4u;
    }
    if (!stage1VramHit) {
        ctx->cachedOpticsValid = false;
        VkBuffer effectiveSrcBuffer = srcBuffer;
        if (runCustomPre) {
            if (!fuji_vk_custom_spv_prepare_stage(ctx, 0u, srcBuffer, optics->srcWidth, optics->srcHeight, &effectiveSrcBuffer)) {
                return FUJI_VK_ERR_OUT_OF_MEMORY;
            }
        }

        if (runFilter) {
            FujiVkFilterStackParams fp = *filter;
            normalize_filter_params(fp, optics->srcWidth, optics->srcHeight);
            std::memcpy(ctx->bufFilterUbo.mapped, &fp, sizeof(FujiVkFilterStackParams));
            ctx->updateDescriptorSet(ds[3], effectiveSrcBuffer, inBytes, ctx->bufFilter.buffer, inBytes, ctx->bufFilterUbo.buffer, sizeof(FujiVkFilterStackParams));
        }

        std::memcpy(ctx->bufOpticsUbo.mapped, &effOptics, sizeof(FujiVkOpticsParams));
        VkBuffer warpIn = runFilter ? ctx->bufFilter.buffer : effectiveSrcBuffer;
        VkBuffer warpOut = runBokeh ? ctx->bufMid.buffer : ctx->bufOut.buffer;
        ctx->updateDescriptorSet(ds[0], warpIn, inBytes, warpOut, dstRgbBytes, ctx->bufOpticsUbo.buffer, sizeof(FujiVkOpticsParams));
        if (runBokehDown) {
            ctx->updateDescriptorSetWithOffsets(
                ds[7],
                ctx->bufMid.buffer, 0, dstRgbBytes,
                ctx->bufMid.buffer, halfOffBytes, halfBytes,
                ctx->bufOpticsUbo.buffer, sizeof(FujiVkOpticsParams));
        }
        if (runBokeh) {
            ctx->updateDescriptorSet(ds[1], ctx->bufMid.buffer, midTotalBytes, ctx->bufOut.buffer, dstRgbBytes, ctx->bufOpticsUbo.buffer, sizeof(FujiVkOpticsParams));
        }
        if (runCustomPost) {
            VkBuffer postOutIgnored = nullptr;
            if (!fuji_vk_custom_spv_prepare_stage(ctx, 1u, ctx->bufOut.buffer, optics->dstWidth, optics->dstHeight, &postOutIgnored)) {
                return FUJI_VK_ERR_OUT_OF_MEMORY;
            }
        }
    }

    if (!reuseBayer) {
        /* Pass 3 writes 12-bit Bayer samples directly into DEVICE_LOCAL bufIspInDevice at byte offset 16384 (word 4096)! */
        std::memcpy(ctx->bufCmosUbo.mapped, &fusedCmos, sizeof(FujiVkCmosParams));
        ctx->updateDescriptorSetWithOffsets(
            ds[2],
            ctx->bufOut.buffer, 0, dstRgbBytes,
            ctx->bufIspInDevice.buffer, lutBytes, bayerU32Bytes,
            ctx->bufCmosUbo.buffer, sizeof(FujiVkCmosParams));
    }

    /* Pass 4 reads packed LUT [0..4095] + DEVICE_LOCAL Bayer [4096..] from bufIspInDevice -> UYVY422 pairs */
    std::memcpy(ctx->bufIspUbo.mapped, &p, sizeof(FujiVkIspDemosaicParams));
    VkBuffer demosaicOut = runPeaking ? ctx->bufIspMid.buffer : ctx->bufIspOut.buffer;
    ctx->updateDescriptorSet(ds[4], ctx->bufIspInDevice.buffer, ispInBytes, demosaicOut, outPairsBytes, ctx->bufIspUbo.buffer, sizeof(FujiVkIspDemosaicParams));
    if (runPeaking) {
        ctx->updateDescriptorSet(ds[5], ctx->bufIspMid.buffer, outPairsBytes, ctx->bufIspOut.buffer, outPairsBytes, ctx->bufIspUbo.buffer, sizeof(FujiVkIspDemosaicParams));
    }
    if (runStats) {
        std::memcpy(ctx->bufIspStatsUbo.mapped, &sp, sizeof(FujiVkIspStatsParams));
        ctx->updateDescriptorSetWithOffsets(
            ds[6],
            ctx->bufIspInDevice.buffer, lutBytes, bayerU32Bytes,
            ctx->bufIspStatsDevice.buffer, 0, FUJI_VK_STATS_OUTPUT_BYTES,
            ctx->bufIspStatsUbo.buffer, sizeof(FujiVkIspStatsParams));
    }

    ctx->vk.resetCommandBuffer(cmdBuf, 0);
    VkCommandBufferBeginInfo cbbi{};
    cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    cbbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (ctx->vk.beginCommandBuffer(cmdBuf, &cbbi) != VK_SUCCESS) {
        ctx->setError("vkBeginCommandBuffer failed");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    VkMemoryBarrier mb{};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

    /* Prepare DEVICE_LOCAL ISP LUT (only when dirty) and zero DEVICE_LOCAL stats atomicAdd buffer in VRAM */
    if (needLutCopyToDevice) {
        VkBufferCopy lutCopy{};
        lutCopy.srcOffset = 0;
        lutCopy.dstOffset = 0;
        lutCopy.size = lutBytes;
        ctx->vk.cmdCopyBuffer(cmdBuf, ctx->bufIspIn.buffer, ctx->bufIspInDevice.buffer, 1, &lutCopy);
    }
    if (runStats) {
        ctx->vk.cmdFillBuffer(cmdBuf, ctx->bufIspStatsDevice.buffer, 0, FUJI_VK_STATS_OUTPUT_BYTES, 0u);
    }
    if (needLutCopyToDevice || runStats) {
        VkMemoryBarrier xferToComp{};
        xferToComp.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        xferToComp.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        xferToComp.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &xferToComp, 0, nullptr, 0, nullptr);
    }

    const uint32_t ogx = (optics->dstWidth + 7u) / 8u;
    const uint32_t ogy = (optics->dstHeight + 7u) / 8u;

    /* Stage 1 (Pass 0..2): Optical Filter + Warp + Pupil Bokeh into DEVICE_LOCAL bufOut.
     * Skipped completely (0.00 ms) on cachedOptics hit when only AE/ISO/WB/FilmSim/Peaking changed! */
    if (!stage1VramHit) {
        /* Pass 0a (Optional Plugin SPI v1.0): Custom SPIR-V PRE_OPTICS_FILTER on DEVICE_LOCAL VRAM */
        if (runCustomPre) {
            fuji_vk_custom_spv_record_stage(ctx, cmdBuf, 0u, nullptr);
        }

        /* Pass 0: Optional Optical Filter Stack */
        if (runFilter) {
            ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelines[FUJI_VK_SHADER_OPTICAL_FILTER_STACK]);
            ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[3], 0, nullptr);
            ctx->vk.cmdDispatch(cmdBuf, (optics->srcWidth + 7u) / 8u, (optics->srcHeight + 7u) / 8u, 1);
            ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        }

        /* Pass 1: Optical Warp + CA + Vignetting (zero-branch specialized) */
        ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->getSpecializedWarpPipeline(optics));
        ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[0], 0, nullptr);
        ctx->vk.cmdDispatch(cmdBuf, ogx, ogy, 1);

        /* Pass 2: Optional 2D Pupil Depth Bokeh (with 2x2 energy-preserving half-res pyramid for cocPx > 8) */
        if (runBokeh) {
            ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
            if (runBokehDown) {
                const uint32_t hgx = ((optics->dstWidth >> 1u) + 7u) / 8u;
                const uint32_t hgy = ((optics->dstHeight >> 1u) + 7u) / 8u;
                ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelines[FUJI_VK_SHADER_BOKEH_DOWNSAMPLE]);
                ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[7], 0, nullptr);
                ctx->vk.cmdDispatch(cmdBuf, hgx, hgy, 1);
                ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
            }
            ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->getSpecializedBokehPipeline(&effOptics));
            ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[1], 0, nullptr);
            ctx->vk.cmdDispatch(cmdBuf, ogx, ogy, 1);
        }

        /* Pass 2b (Optional Plugin SPI v1.0): Custom SPIR-V POST_OPTICS_RGB on DEVICE_LOCAL VRAM */
        if (runCustomPost) {
            ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
            fuji_vk_custom_spv_record_stage(ctx, cmdBuf, 1u, ctx->bufOut.buffer);
        }

        ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
    }

    if (!reuseBayer) {
        /* Pass 3: CMOS Bayer directly into bufIspIn + 16384 */
        ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelines[FUJI_VK_SHADER_CMOS_BAYER]);
        ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[2], 0, nullptr);
        ctx->vk.cmdDispatch(cmdBuf, ogx, ogy, 1);
    }

    /* Pass 4: ISP Demosaic + CCM + Tone LUT + RGB->YCbCr directly from GPU-resident Bayer (specialized) */
    const uint32_t igx = static_cast<uint32_t>((pairsW + 7u) / 8u);
    const uint32_t igy = (p.dstHeight + 7u) / 8u;
    ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
    ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->getSpecializedIspDemosaicPipeline(p));
    ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[4], 0, nullptr);
    ctx->vk.cmdDispatch(cmdBuf, igx, igy, 1);

    /* Pass 5: Optional 5-tap HPF Focus Peaking */
    if (runPeaking) {
        ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelines[FUJI_VK_SHADER_ISP_PEAKING]);
        ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[5], 0, nullptr);
        ctx->vk.cmdDispatch(cmdBuf, igx, igy, 1);
    }

    /* Pass 6: Optional GPU-Resident ISP Stats Reduction (reads DEVICE_LOCAL Bayer, atomicAdd into DEVICE_LOCAL VRAM, copies 9,280 B to readback) */
    if (runStats) {
        const uint32_t stepPx = std::max(sp.stepQuad, 1u) << 1u;
        const uint32_t quadsX = (sp.viewW + stepPx - 1u) / stepPx;
        const uint32_t quadsY = (sp.viewH + stepPx - 1u) / stepPx;
        const uint32_t sgx = std::max((quadsX + 15u) / 16u, 1u);
        const uint32_t sgy = std::max((quadsY + 15u) / 16u, 1u);
        ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->statsPipeline);
        ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[6], 0, nullptr);
        ctx->vk.cmdDispatch(cmdBuf, sgx, sgy, 1u);

        VkMemoryBarrier compToXfer{};
        compToXfer.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        compToXfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        compToXfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &compToXfer, 0, nullptr, 0, nullptr);

        VkBufferCopy statsCopy{};
        statsCopy.srcOffset = 0;
        statsCopy.dstOffset = 0;
        statsCopy.size = FUJI_VK_STATS_OUTPUT_BYTES;
        ctx->vk.cmdCopyBuffer(cmdBuf, ctx->bufIspStatsDevice.buffer, ctx->bufIspStatsOut.buffer, 1, &statsCopy);
    }

    if (ctx->vk.endCommandBuffer(cmdBuf) != VK_SUCCESS || !ctx->submitAndWait(slot)) {
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }
    if (!reuseBayer) {
        ctx->cachedIspBayerParams = fusedCmos;
        ctx->cachedIspBayerValid = opticsStage1Key != 0ULL;
    }
    if (needLutCopyToDevice) {
        ctx->cachedLutDeviceSynced = true;
    }
    if (!stage1VramHit) {
        ctx->cachedOpticsKey = opticsStage1Key;
        ctx->cachedOpticsBytes = dstRgbBytes;
        ctx->cachedOpticsValid = (opticsStage1Key != 0ULL);
    }

    ctx->invalidateReadback(ctx->bufIspOut);
    const uint32_t* pairs = reinterpret_cast<const uint32_t*>(ctx->bufIspOut.mapped);
    if (mapped_uyvy) *mapped_uyvy = reinterpret_cast<const uint8_t*>(pairs);
    else pack_isp_pairs_to_yuv(pairs, p, out_y_or_packed, out_uv_plane, stride, rowBytes, pairsW);
    if (runStats) {
        ctx->invalidateReadback(ctx->bufIspStatsOut);
        std::memcpy(out_stats, ctx->bufIspStatsOut.mapped, FUJI_VK_STATS_OUTPUT_BYTES);
    }

    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) {
        *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    }
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_render_optics_to_isp_yuv_with_stats(
    FujiVkContext* ctx,
    const float* in_rgba_depth,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    const int32_t* lut_r_4096,
    const int32_t* lut_g_4096,
    const int32_t* lut_b_4096,
    uint8_t* out_y_or_packed,
    size_t out_y_or_packed_bytes,
    uint8_t* out_uv_plane,
    size_t out_uv_plane_bytes,
    const FujiVkIspDemosaicParams* isp_params,
    const FujiVkIspStatsParams* stats_params,
    FujiVkIspStatsOutput* out_stats,
    double* out_gpu_ms) {
    if (!ctx || !in_rgba_depth || !optics) return FUJI_VK_ERR_INVALID_ARG;
    if (optics->srcWidth == 0 || optics->srcHeight == 0) {
        ctx->setError("Invalid optics source dimensions");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const size_t srcPixels = static_cast<size_t>(optics->srcWidth) * optics->srcHeight;
    const VkDeviceSize inBytes = srcPixels * 16ULL;
    if (!ctx->ensureHostBuffer(ctx->bufIn, inBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    std::memcpy(ctx->bufIn.mapped, in_rgba_depth, inBytes);
    return dispatch_optics_to_isp_yuv_internal(
        ctx, ctx->bufIn.buffer, inBytes, 0ULL, filter, optics, cmos,
        lut_r_4096, lut_g_4096, lut_b_4096,
        out_y_or_packed, out_y_or_packed_bytes, out_uv_plane, out_uv_plane_bytes,
        isp_params, stats_params, out_stats, out_gpu_ms);
}

FUJI_VK_EXPORT int32_t fuji_vk_render_optics_to_isp_yuv(
    FujiVkContext* ctx,
    const float* in_rgba_depth,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    const int32_t* lut_r_4096,
    const int32_t* lut_g_4096,
    const int32_t* lut_b_4096,
    uint8_t* out_y_or_packed,
    size_t out_y_or_packed_bytes,
    uint8_t* out_uv_plane,
    size_t out_uv_plane_bytes,
    const FujiVkIspDemosaicParams* isp_params,
    double* out_gpu_ms) {
    return fuji_vk_render_optics_to_isp_yuv_with_stats(
        ctx, in_rgba_depth, filter, optics, cmos,
        lut_r_4096, lut_g_4096, lut_b_4096,
        out_y_or_packed, out_y_or_packed_bytes, out_uv_plane, out_uv_plane_bytes,
        isp_params, nullptr, nullptr, out_gpu_ms);
}

FUJI_VK_EXPORT int32_t fuji_vk_resize_1024_to_640_422(
    FujiVkContext* ctx, const uint8_t* source, size_t source_bytes, uint32_t src_stride,
    uint8_t* destination, size_t destination_bytes, uint32_t yyuv, double* timings_ms) {
    constexpr size_t out_bytes = 640u * 360u * 2u;
    if (!ctx || !source || !destination || src_stride < 2048u || src_stride > 0x3ffffu ||
        (src_stride & 3u) || yyuv > 1u || destination_bytes < out_bytes)
        return FUJI_VK_ERR_INVALID_ARG;
    const size_t in_bytes = static_cast<size_t>(575u) * src_stride + 2048u;
    if (source_bytes < in_bytes) return FUJI_VK_ERR_INVALID_ARG;
    if (!ctx->uniReszPipeline || !ctx->ensureHostBuffer(ctx->bufUniReszIn, in_bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) ||
        !ctx->ensureReadbackBuffer(ctx->bufUniReszOut, out_bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) ||
        !ctx->ensureHostBuffer(ctx->bufUniReszParams, 16, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT))
        return FUJI_VK_ERR_DISPATCH_FAILED;

    const auto start = std::chrono::steady_clock::now();
    const uint32_t params[4] = {src_stride / 4u, yyuv, 0u, 0u};
    std::memcpy(ctx->bufUniReszIn.mapped, source, in_bytes);
    std::memcpy(ctx->bufUniReszParams.mapped, params, sizeof(params));
    const auto uploaded = std::chrono::steady_clock::now();
    ctx->drainAllAsyncSlots();
    const uint32_t slot = ctx->nextSlot();
    const auto cmd = ctx->cmdBufs[slot];
    const auto ds = ctx->descSets[slot][0];
    ctx->updateDescriptorSet(ds, ctx->bufUniReszIn.buffer, in_bytes, ctx->bufUniReszOut.buffer, out_bytes,
                             ctx->bufUniReszParams.buffer, sizeof(params));
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (ctx->vk.resetCommandBuffer(cmd, 0) != VK_SUCCESS ||
        ctx->vk.beginCommandBuffer(cmd, &begin) != VK_SUCCESS) return FUJI_VK_ERR_DISPATCH_FAILED;
    ctx->vk.cmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->uniReszPipeline);
    ctx->vk.cmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds, 0, nullptr);
    ctx->vk.cmdDispatch(cmd, 10, 90, 1);
    if (ctx->vk.endCommandBuffer(cmd) != VK_SUCCESS || !ctx->submitAndWait(slot)) return FUJI_VK_ERR_DISPATCH_FAILED;
    ctx->invalidateReadback(ctx->bufUniReszOut);
    const auto complete = std::chrono::steady_clock::now();
    std::memcpy(destination, ctx->bufUniReszOut.mapped, out_bytes);
    if (timings_ms) {
        using Ms = std::chrono::duration<double, std::milli>;
        timings_ms[0] = Ms(uploaded - start).count();
        timings_ms[1] = Ms(complete - uploaded).count();
        timings_ms[2] = Ms(std::chrono::steady_clock::now() - complete).count();
    }
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_upload_scene(
    FujiVkContext* ctx,
    uint64_t scene_key,
    const float* in_rgba_depth,
    size_t src_pixel_count,
    double* out_gpu_ms) {
    if (!ctx || !in_rgba_depth || scene_key == 0ULL || src_pixel_count == 0u) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const VkDeviceSize inBytes = static_cast<VkDeviceSize>(src_pixel_count) * 16ULL;
    if (ctx->cachedSceneValid && ctx->cachedSceneKey == scene_key && ctx->cachedSceneBytes == inBytes && ctx->bufSceneDevice.buffer) {
        if (out_gpu_ms) *out_gpu_ms = 0.0;
        return FUJI_VK_OK;
    }
    ctx->cachedOpticsValid = false;
    if (!ctx->ensureHostBuffer(ctx->bufIn, inBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT) ||
        !ctx->ensureDeviceBuffer(ctx->bufSceneDevice, inBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
        ctx->cachedSceneValid = false;
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    auto t0 = std::chrono::steady_clock::now();
    std::memcpy(ctx->bufIn.mapped, in_rgba_depth, inBytes);

    const uint32_t slot = ctx->nextSlot();
    VkCommandBuffer cmdBuf = ctx->cmdBufs[slot];
    ctx->vk.resetCommandBuffer(cmdBuf, 0);
    VkCommandBufferBeginInfo cbbi{};
    cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    cbbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (ctx->vk.beginCommandBuffer(cmdBuf, &cbbi) != VK_SUCCESS) {
        ctx->cachedSceneValid = false;
        ctx->setError("vkBeginCommandBuffer failed for scene upload");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }
    VkBufferCopy region{};
    region.srcOffset = 0;
    region.dstOffset = 0;
    region.size = inBytes;
    ctx->vk.cmdCopyBuffer(cmdBuf, ctx->bufIn.buffer, ctx->bufSceneDevice.buffer, 1, &region);
    if (ctx->vk.endCommandBuffer(cmdBuf) != VK_SUCCESS || !ctx->submitAndWait(slot)) {
        ctx->cachedSceneValid = false;
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }
    ctx->cachedSceneKey = scene_key;
    ctx->cachedSceneBytes = inBytes;
    ctx->cachedSceneValid = true;
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) {
        *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    }
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_render_cached_scene_optics(
    FujiVkContext* ctx,
    uint64_t scene_key,
    float* out_rgba,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    double* out_gpu_ms) {
    if (!ctx || !out_rgba || !optics || scene_key == 0ULL) return FUJI_VK_ERR_INVALID_ARG;
    if (optics->srcWidth == 0 || optics->srcHeight == 0 || optics->dstWidth == 0 || optics->dstHeight == 0) {
        ctx->setError("Invalid optics dimensions");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const VkDeviceSize inBytes = static_cast<VkDeviceSize>(optics->srcWidth) * optics->srcHeight * 16ULL;
    if (!ctx->cachedSceneValid || ctx->cachedSceneKey != scene_key || ctx->cachedSceneBytes != inBytes || !ctx->bufSceneDevice.buffer) {
        ctx->setError("Cached VRAM scene miss for requested scene_key");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    return dispatch_optics_pipeline_internal(ctx, ctx->bufSceneDevice.buffer, inBytes, filter, optics, nullptr, out_rgba, nullptr, out_gpu_ms);
}

FUJI_VK_EXPORT int32_t fuji_vk_render_cached_scene_to_bayer(
    FujiVkContext* ctx,
    uint64_t scene_key,
    uint8_t* out_bayer,
    size_t out_bayer_bytes,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    double* out_gpu_ms) {
    if (!ctx || !out_bayer || !optics || !cmos || scene_key == 0ULL) return FUJI_VK_ERR_INVALID_ARG;
    if (optics->dstWidth != cmos->width || optics->dstHeight != cmos->height || optics->srcWidth == 0 || optics->srcHeight == 0) {
        ctx->setError("Optics dst dimensions must match CMOS dimensions");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    if (cmos->bitDepth < 8 || cmos->bitDepth > 16) {
        ctx->setError("Invalid CMOS bitDepth");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const size_t dstPixels = static_cast<size_t>(optics->dstWidth) * optics->dstHeight;
    const size_t bytesPerPx = (cmos->bitDepth <= 8) ? 1u : 2u;
    if (out_bayer_bytes < dstPixels * bytesPerPx) {
        ctx->setError("out_bayer_bytes too small");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const VkDeviceSize inBytes = static_cast<VkDeviceSize>(optics->srcWidth) * optics->srcHeight * 16ULL;
    if (!ctx->cachedSceneValid || ctx->cachedSceneKey != scene_key || ctx->cachedSceneBytes != inBytes || !ctx->bufSceneDevice.buffer) {
        ctx->setError("Cached VRAM scene miss for requested scene_key");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    return dispatch_optics_pipeline_internal(ctx, ctx->bufSceneDevice.buffer, inBytes, filter, optics, cmos, nullptr, out_bayer, out_gpu_ms);
}

static int32_t dispatch_cached_isp_yuv(
    FujiVkContext* ctx,
    uint64_t scene_key,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    const int32_t* lut_r_4096,
    const int32_t* lut_g_4096,
    const int32_t* lut_b_4096,
    uint8_t* out_y_or_packed,
    size_t out_y_or_packed_bytes,
    uint8_t* out_uv_plane,
    size_t out_uv_plane_bytes,
    const FujiVkIspDemosaicParams* isp_params,
    const FujiVkIspStatsParams* stats_params,
    FujiVkIspStatsOutput* out_stats,
    double* out_gpu_ms, const uint8_t** mapped_uyvy) {
    if (!ctx || !optics || scene_key == 0ULL) return FUJI_VK_ERR_INVALID_ARG;
    if (optics->srcWidth == 0 || optics->srcHeight == 0) {
        ctx->setError("Invalid optics source dimensions");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const VkDeviceSize inBytes = static_cast<VkDeviceSize>(optics->srcWidth) * optics->srcHeight * 16ULL;
    if (!ctx->cachedSceneValid || ctx->cachedSceneKey != scene_key || ctx->cachedSceneBytes != inBytes || !ctx->bufSceneDevice.buffer) {
        ctx->setError("Cached VRAM scene miss for requested scene_key");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    return dispatch_optics_to_isp_yuv_internal(
        ctx, ctx->bufSceneDevice.buffer, inBytes, scene_key, filter, optics, cmos,
        lut_r_4096, lut_g_4096, lut_b_4096,
        out_y_or_packed, out_y_or_packed_bytes, out_uv_plane, out_uv_plane_bytes,
        isp_params, stats_params, out_stats, out_gpu_ms, mapped_uyvy);
}

FUJI_VK_EXPORT int32_t fuji_vk_render_cached_scene_to_isp_yuv_with_stats(
    FujiVkContext* ctx,
    uint64_t scene_key,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    const int32_t* lut_r_4096,
    const int32_t* lut_g_4096,
    const int32_t* lut_b_4096,
    uint8_t* out_y_or_packed,
    size_t out_y_or_packed_bytes,
    uint8_t* out_uv_plane,
    size_t out_uv_plane_bytes,
    const FujiVkIspDemosaicParams* isp_params,
    const FujiVkIspStatsParams* stats_params,
    FujiVkIspStatsOutput* out_stats,
    double* out_gpu_ms) {
    return dispatch_cached_isp_yuv(ctx, scene_key, filter, optics, cmos, lut_r_4096, lut_g_4096, lut_b_4096,
        out_y_or_packed, out_y_or_packed_bytes, out_uv_plane, out_uv_plane_bytes,
        isp_params, stats_params, out_stats, out_gpu_ms, nullptr);
}

FUJI_VK_EXPORT int32_t fuji_vk_render_cached_scene_to_isp_uyvy_view(
    FujiVkContext* ctx, uint64_t scene_key, const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics, const FujiVkCmosParams* cmos,
    const int32_t* lut_r_4096, const int32_t* lut_g_4096, const int32_t* lut_b_4096,
    const FujiVkIspDemosaicParams* isp_params, const FujiVkIspStatsParams* stats_params,
    FujiVkIspStatsOutput* out_stats, const uint8_t** mapped_uyvy, size_t dst_capacity, double* out_gpu_ms) {
    if (!mapped_uyvy) return FUJI_VK_ERR_INVALID_ARG;
    *mapped_uyvy = nullptr;
    if (!isp_params || isp_params->outputFormat != FUJI_VK_ISP_FMT_UYVY422) return FUJI_VK_ERR_INVALID_ARG;
    return dispatch_cached_isp_yuv(ctx, scene_key, filter, optics, cmos, lut_r_4096, lut_g_4096, lut_b_4096,
        nullptr, dst_capacity, nullptr, 0, isp_params, stats_params, out_stats, out_gpu_ms, mapped_uyvy);
}

FUJI_VK_EXPORT int32_t fuji_vk_render_cached_scene_to_isp_yuv(
    FujiVkContext* ctx,
    uint64_t scene_key,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    const int32_t* lut_r_4096,
    const int32_t* lut_g_4096,
    const int32_t* lut_b_4096,
    uint8_t* out_y_or_packed,
    size_t out_y_or_packed_bytes,
    uint8_t* out_uv_plane,
    size_t out_uv_plane_bytes,
    const FujiVkIspDemosaicParams* isp_params,
    double* out_gpu_ms) {
    return fuji_vk_render_cached_scene_to_isp_yuv_with_stats(
        ctx, scene_key, filter, optics, cmos,
        lut_r_4096, lut_g_4096, lut_b_4096,
        out_y_or_packed, out_y_or_packed_bytes, out_uv_plane, out_uv_plane_bytes,
        isp_params, nullptr, nullptr, out_gpu_ms);
}

FUJI_VK_EXPORT int32_t fuji_vk_submit_async_to_bayer(
    FujiVkContext* ctx,
    uint64_t scene_key,
    const float* in_rgba_depth,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    uint64_t* out_ticket) {
    if (!ctx || !optics || !cmos || !out_ticket) return FUJI_VK_ERR_INVALID_ARG;
    *out_ticket = 0ULL;
    if (optics->srcWidth == 0 || optics->srcHeight == 0 ||
        optics->dstWidth != cmos->width || optics->dstHeight != cmos->height ||
        cmos->bitDepth < 8 || cmos->bitDepth > 16) {
        ctx->setError("Invalid optics/CMOS dimensions or bitDepth for async submit");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const size_t srcPixels = static_cast<size_t>(optics->srcWidth) * optics->srcHeight;
    const size_t dstPixels = static_cast<size_t>(optics->dstWidth) * optics->dstHeight;
    const VkDeviceSize srcBytes = srcPixels * 16ULL;
    const VkDeviceSize dstBytes = dstPixels * 16ULL;
    const VkDeviceSize bayerU32Bytes = dstPixels * 4ULL;

    const bool useCachedScene = (scene_key != 0ULL && ctx->cachedSceneValid &&
                                 ctx->cachedSceneKey == scene_key &&
                                 ctx->cachedSceneBytes == srcBytes &&
                                 ctx->bufSceneDevice.buffer != nullptr);
    if (!useCachedScene && !in_rgba_depth) {
        ctx->setError("Async submit requires either a valid cached scene_key or non-null in_rgba_depth");
        return FUJI_VK_ERR_INVALID_ARG;
    }

    const uint32_t slotIdx = ctx->nextSlot();
    auto& slot = ctx->asyncSlots[slotIdx];
    if (slot.inFlight) {
        /* Slot still busy from 2 frames ago -> wait only on this slot's fence */
        ctx->vk.waitForFences(ctx->device, 1, &ctx->fences[slotIdx], VK_TRUE, 10000000000ULL);
        slot.inFlight = false;
        slot.ticket = 0ULL;
    }

    const bool runFilter = (filter != nullptr) && (filter->enableFlags != 0u);
    const bool runBokeh = ((optics->flags & 2u) != 0u) && (optics->maxBokehPx >= 0.5f);
    const bool runBokehDown = runBokeh && (optics->maxBokehPx > 8.5f) &&
        (optics->dstWidth >= 16u) && (optics->dstHeight >= 16u) &&
        ((optics->dstWidth & 1u) == 0u) && ((optics->dstHeight & 1u) == 0u);
    const size_t halfBaseVec4 = (dstPixels + 15u) & ~static_cast<size_t>(15u);
    const VkDeviceSize halfOffBytes = static_cast<VkDeviceSize>(halfBaseVec4) * 16ULL;
    const VkDeviceSize halfBytes = static_cast<VkDeviceSize>(optics->dstWidth >> 1u) * static_cast<VkDeviceSize>(optics->dstHeight >> 1u) * 16ULL;
    const VkDeviceSize midTotalBytes = runBokehDown ? (halfOffBytes + halfBytes) : dstBytes;

    if (!useCachedScene && !ctx->ensureHostBuffer(slot.bufIn, srcBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    if ((runFilter && !ctx->ensureDeviceBuffer(slot.bufFilter, srcBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) ||
        (runBokeh && !ctx->ensureDeviceBuffer(slot.bufMid, midTotalBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) ||
        !ctx->ensureDeviceBuffer(slot.bufOut, dstBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) ||
        !ctx->ensureReadbackBuffer(slot.bufBayer, bayerU32Bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) ||
        !ctx->ensureHostBuffer(slot.bufFilterUbo, sizeof(FujiVkFilterStackParams), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT) ||
        !ctx->ensureHostBuffer(slot.bufOpticsUbo, sizeof(FujiVkOpticsParams), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT) ||
        !ctx->ensureHostBuffer(slot.bufCmosUbo, sizeof(FujiVkCmosParams), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    slot.submitTime = std::chrono::steady_clock::now();
    VkBuffer srcBuffer = useCachedScene ? ctx->bufSceneDevice.buffer : slot.bufIn.buffer;
    if (!useCachedScene) {
        std::memcpy(slot.bufIn.mapped, in_rgba_depth, srcBytes);
    }

    VkDescriptorSet* ds = ctx->descSets[slotIdx];
    if (runFilter) {
        FujiVkFilterStackParams fp = *filter;
        if (fp.width == 0) fp.width = optics->srcWidth;
        if (fp.height == 0) fp.height = optics->srcHeight;
        std::memcpy(slot.bufFilterUbo.mapped, &fp, sizeof(FujiVkFilterStackParams));
        ctx->updateDescriptorSet(ds[3], srcBuffer, srcBytes, slot.bufFilter.buffer, srcBytes, slot.bufFilterUbo.buffer, sizeof(FujiVkFilterStackParams));
    }
    FujiVkOpticsParams effOptics = *optics;
    if (runBokehDown) {
        effOptics.flags |= 4u;
    }
    std::memcpy(slot.bufOpticsUbo.mapped, &effOptics, sizeof(FujiVkOpticsParams));
    VkBuffer warpIn = runFilter ? slot.bufFilter.buffer : srcBuffer;
    VkBuffer warpOut = runBokeh ? slot.bufMid.buffer : slot.bufOut.buffer;
    ctx->updateDescriptorSet(ds[0], warpIn, srcBytes, warpOut, dstBytes, slot.bufOpticsUbo.buffer, sizeof(FujiVkOpticsParams));
    if (runBokehDown) {
        ctx->updateDescriptorSetWithOffsets(
            ds[7],
            slot.bufMid.buffer, 0, dstBytes,
            slot.bufMid.buffer, halfOffBytes, halfBytes,
            slot.bufOpticsUbo.buffer, sizeof(FujiVkOpticsParams));
    }
    if (runBokeh) {
        ctx->updateDescriptorSet(ds[1], slot.bufMid.buffer, midTotalBytes, slot.bufOut.buffer, dstBytes, slot.bufOpticsUbo.buffer, sizeof(FujiVkOpticsParams));
    }
    std::memcpy(slot.bufCmosUbo.mapped, cmos, sizeof(FujiVkCmosParams));
    ctx->updateDescriptorSet(ds[2], slot.bufOut.buffer, dstBytes, slot.bufBayer.buffer, bayerU32Bytes, slot.bufCmosUbo.buffer, sizeof(FujiVkCmosParams));

    VkCommandBuffer cmdBuf = ctx->cmdBufs[slotIdx];
    ctx->vk.resetCommandBuffer(cmdBuf, 0);
    VkCommandBufferBeginInfo cbbi{};
    cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    cbbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (ctx->vk.beginCommandBuffer(cmdBuf, &cbbi) != VK_SUCCESS) {
        ctx->setError("vkBeginCommandBuffer failed for async submit");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    VkMemoryBarrier mb{};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

    if (runFilter) {
        ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelines[FUJI_VK_SHADER_OPTICAL_FILTER_STACK]);
        ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[3], 0, nullptr);
        ctx->vk.cmdDispatch(cmdBuf, (optics->srcWidth + 7u) / 8u, (optics->srcHeight + 7u) / 8u, 1);
        ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
    }

    const uint32_t gx = (optics->dstWidth + 7u) / 8u;
    const uint32_t gy = (optics->dstHeight + 7u) / 8u;
    ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->getSpecializedWarpPipeline(&effOptics));
    ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[0], 0, nullptr);
    ctx->vk.cmdDispatch(cmdBuf, gx, gy, 1);

    if (runBokeh) {
        ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        if (runBokehDown) {
            const uint32_t hgx = ((optics->dstWidth >> 1u) + 7u) / 8u;
            const uint32_t hgy = ((optics->dstHeight >> 1u) + 7u) / 8u;
            ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelines[FUJI_VK_SHADER_BOKEH_DOWNSAMPLE]);
            ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[7], 0, nullptr);
            ctx->vk.cmdDispatch(cmdBuf, hgx, hgy, 1);
            ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        }
        ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->getSpecializedBokehPipeline(&effOptics));
        ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[1], 0, nullptr);
        ctx->vk.cmdDispatch(cmdBuf, gx, gy, 1);
    }

    ctx->vk.cmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
    ctx->vk.cmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelines[FUJI_VK_SHADER_CMOS_BAYER]);
    ctx->vk.cmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ds[2], 0, nullptr);
    ctx->vk.cmdDispatch(cmdBuf, gx, gy, 1);

    if (ctx->vk.endCommandBuffer(cmdBuf) != VK_SUCCESS) {
        ctx->setError("vkEndCommandBuffer failed for async submit");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    ctx->vk.resetFences(ctx->device, 1, &ctx->fences[slotIdx]);
    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmdBuf;
    VkResult r = ctx->vk.queueSubmit(ctx->computeQueue, 1, &si, ctx->fences[slotIdx]);
    if (r != VK_SUCCESS) {
        ctx->setError("vkQueueSubmit failed for async submit: " + std::to_string(r));
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    const uint64_t ticket = ctx->nextAsyncTicket++;
    slot.inFlight = true;
    slot.ticket = ticket;
    slot.dstPixels = dstPixels;
    slot.bitDepth = cmos->bitDepth;
    ctx->pipelineStats.asyncFramesSubmitted++;
    *out_ticket = ticket;
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_collect_async_bayer(
    FujiVkContext* ctx,
    uint64_t ticket,
    uint8_t* out_bayer,
    size_t out_bayer_bytes,
    double* out_gpu_ms) {
    if (!ctx || ticket == 0ULL || !out_bayer) return FUJI_VK_ERR_INVALID_ARG;
    int32_t foundSlot = -1;
    for (uint32_t s = 0; s < FujiVkContext::kFrameSlots; ++s) {
        if (ctx->asyncSlots[s].inFlight && ctx->asyncSlots[s].ticket == ticket) {
            foundSlot = static_cast<int32_t>(s);
            break;
        }
    }
    if (foundSlot < 0) {
        ctx->setError("Async ticket not found or already collected");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    auto& slot = ctx->asyncSlots[static_cast<uint32_t>(foundSlot)];
    const size_t bytesPerPx = (slot.bitDepth <= 8u) ? 1u : 2u;
    if (out_bayer_bytes < slot.dstPixels * bytesPerPx) {
        ctx->setError("out_bayer_bytes too small for async collect");
        return FUJI_VK_ERR_INVALID_ARG;
    }

    VkResult r = ctx->vk.waitForFences(ctx->device, 1, &ctx->fences[static_cast<uint32_t>(foundSlot)], VK_TRUE, 10000000000ULL);
    slot.inFlight = false;
    slot.ticket = 0ULL;
    if (r != VK_SUCCESS) {
        ctx->setError("vkWaitForFences failed for async collect: " + std::to_string(r));
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }
    ctx->invalidateReadback(slot.bufBayer);
    pack_bayer_u32_to_bytes(reinterpret_cast<const uint32_t*>(slot.bufBayer.mapped), out_bayer, slot.dstPixels, slot.bitDepth);
    ctx->pipelineStats.asyncFramesCollected++;
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) {
        *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - slot.submitTime).count();
    }
    return FUJI_VK_OK;
}

static VkFlags external_handle_type_to_vk_bits(uint32_t handleType) {
    switch (handleType) {
        case FUJI_VK_EXT_HANDLE_WIN32_NT_HANDLE:  return VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        case FUJI_VK_EXT_HANDLE_WIN32_KMT_HANDLE: return VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_KMT_BIT;
        case FUJI_VK_EXT_HANDLE_WIN32_D3D11_TEX:  return VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT;
        case FUJI_VK_EXT_HANDLE_OPAQUE_FD:        return VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
        case FUJI_VK_EXT_HANDLE_DMA_BUF_FD:       return VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;
        default:                                  return 0u;
    }
}

static bool is_win32_external_handle(uint32_t handleType) {
    return handleType == FUJI_VK_EXT_HANDLE_WIN32_NT_HANDLE ||
           handleType == FUJI_VK_EXT_HANDLE_WIN32_KMT_HANDLE ||
           handleType == FUJI_VK_EXT_HANDLE_WIN32_D3D11_TEX;
}

static bool is_fd_external_handle(uint32_t handleType) {
    return handleType == FUJI_VK_EXT_HANDLE_OPAQUE_FD ||
           handleType == FUJI_VK_EXT_HANDLE_DMA_BUF_FD;
}

FUJI_VK_EXPORT int32_t fuji_vk_validate_external_frame_desc(
    FujiVkContext* ctx,
    const FujiVkExternalFrameDesc* desc) {
    if (!ctx || !desc) return FUJI_VK_ERR_INVALID_ARG;
    if (desc->width == 0 || desc->height == 0 || desc->colorMemorySizeBytes == 0) {
        ctx->setError("External frame requires non-zero width, height, and colorMemorySizeBytes");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    if (is_win32_external_handle(desc->handleType)) {
        if ((ctx->info.externalMemoryCaps & FUJI_VK_EXT_CAP_MEMORY_WIN32) == 0) {
            ctx->setError("Device does not advertise VK_KHR_external_memory_win32");
            return FUJI_VK_ERR_UNSUPPORTED_EXT;
        }
        if (desc->colorHandle == 0) {
            ctx->setError("Win32 external color handle must be non-zero");
            return FUJI_VK_ERR_INVALID_ARG;
        }
        if (desc->depthMemorySizeBytes > 0 && desc->depthHandle == 0) {
            ctx->setError("Win32 external depth handle must be non-zero when depthMemorySizeBytes > 0");
            return FUJI_VK_ERR_INVALID_ARG;
        }
        return FUJI_VK_OK;
    }
    if (is_fd_external_handle(desc->handleType)) {
        if ((ctx->info.externalMemoryCaps & FUJI_VK_EXT_CAP_MEMORY_FD) == 0) {
            ctx->setError("Device does not advertise VK_KHR_external_memory_fd");
            return FUJI_VK_ERR_UNSUPPORTED_EXT;
        }
        if (desc->handleType == FUJI_VK_EXT_HANDLE_DMA_BUF_FD &&
            (ctx->info.externalMemoryCaps & FUJI_VK_EXT_CAP_DMA_BUF) == 0) {
            ctx->setError("Device does not advertise VK_EXT_external_memory_dma_buf");
            return FUJI_VK_ERR_UNSUPPORTED_EXT;
        }
        if (static_cast<int64_t>(desc->colorHandle) < 0) {
            ctx->setError("Linux external color fd must be non-negative");
            return FUJI_VK_ERR_INVALID_ARG;
        }
        if (desc->depthMemorySizeBytes > 0 && static_cast<int64_t>(desc->depthHandle) < 0) {
            ctx->setError("Linux external depth fd must be non-negative when depthMemorySizeBytes > 0");
            return FUJI_VK_ERR_INVALID_ARG;
        }
        return FUJI_VK_OK;
    }
    ctx->setError("Unsupported external handleType");
    return FUJI_VK_ERR_INVALID_ARG;
}

static int32_t import_external_buffer_plane(
    FujiVkContext* ctx,
    uint32_t handleType,
    uint64_t handleVal,
    uint64_t memSizeBytes,
    VkBuffer& outBuf,
    VkDeviceMemory& outMem) {
    VkExternalMemoryBufferCreateInfo embci{};
    embci.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO;
    embci.handleTypes = external_handle_type_to_vk_bits(handleType);

    VkBufferCreateInfo bci{};
    bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bci.pNext = &embci;
    bci.size = memSizeBytes;
    bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VkResult r = ctx->vk.createBuffer(ctx->device, &bci, nullptr, &outBuf);
    if (r != VK_SUCCESS) {
        ctx->setError("vkCreateBuffer for external frame plane failed: " + std::to_string(r));
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkMemoryRequirements req{};
    ctx->vk.getBufferMemoryRequirements(ctx->device, outBuf, &req);
    int32_t memIdx = ctx->findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memIdx < 0) memIdx = ctx->findMemoryType(req.memoryTypeBits, 0);
    if (memIdx < 0) {
        ctx->vk.destroyBuffer(ctx->device, outBuf, nullptr);
        outBuf = nullptr;
        ctx->setError("No compatible Vulkan memory type for external frame import");
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    VkImportMemoryFdInfoKHR fdInfo{};
    VkImportMemoryWin32HandleInfoKHR winInfo{};
    VkMemoryAllocateInfo mai{};
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.allocationSize = std::max<VkDeviceSize>(req.size, memSizeBytes);
    mai.memoryTypeIndex = static_cast<uint32_t>(memIdx);

    if (is_fd_external_handle(handleType)) {
        fdInfo.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR;
        fdInfo.handleType = embci.handleTypes;
        fdInfo.fd = static_cast<int>(handleVal);
        mai.pNext = &fdInfo;
    } else {
        winInfo.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR;
        winInfo.handleType = embci.handleTypes;
        winInfo.handle = reinterpret_cast<void*>(static_cast<uintptr_t>(handleVal));
        mai.pNext = &winInfo;
    }

    r = ctx->vk.allocateMemory(ctx->device, &mai, nullptr, &outMem);
    if (r != VK_SUCCESS) {
        ctx->vk.destroyBuffer(ctx->device, outBuf, nullptr);
        outBuf = nullptr;
        ctx->setError("External memory vkAllocateMemory failed: " + std::to_string(r));
        return FUJI_VK_ERR_INIT_FAILED;
    }
    r = ctx->vk.bindBufferMemory(ctx->device, outBuf, outMem, 0);
    if (r != VK_SUCCESS) {
        ctx->vk.freeMemory(ctx->device, outMem, nullptr);
        ctx->vk.destroyBuffer(ctx->device, outBuf, nullptr);
        outMem = nullptr;
        outBuf = nullptr;
        ctx->setError("External memory vkBindBufferMemory failed: " + std::to_string(r));
        return FUJI_VK_ERR_INIT_FAILED;
    }
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_import_external_frame(
    FujiVkContext* ctx,
    const FujiVkExternalFrameDesc* desc,
    FujiVkExternalFrame** out_frame) {
    if (!ctx || !desc || !out_frame) return FUJI_VK_ERR_INVALID_ARG;
    *out_frame = nullptr;
    int32_t vrc = fuji_vk_validate_external_frame_desc(ctx, desc);
    if (vrc != FUJI_VK_OK) return vrc;

    auto frame = std::make_unique<FujiVkExternalFrame>();
    frame->desc = *desc;

    int32_t rc = import_external_buffer_plane(
        ctx, desc->handleType, desc->colorHandle, desc->colorMemorySizeBytes, frame->colorBuffer, frame->colorMemory);
    if (rc != FUJI_VK_OK) return rc;

    if (desc->depthMemorySizeBytes > 0) {
        rc = import_external_buffer_plane(
            ctx, desc->handleType, desc->depthHandle, desc->depthMemorySizeBytes, frame->depthBuffer, frame->depthMemory);
        if (rc != FUJI_VK_OK) {
            fuji_vk_release_external_frame(ctx, frame.release());
            return rc;
        }
    }

    *out_frame = frame.release();
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_render_external_frame_to_bayer(
    FujiVkContext* ctx,
    const FujiVkExternalFrame* ext_frame,
    uint8_t* out_bayer,
    size_t out_bayer_bytes,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    double* out_gpu_ms) {
    if (!ctx || !ext_frame || !ext_frame->colorBuffer || !out_bayer || !optics || !cmos) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    if (optics->dstWidth != cmos->width || optics->dstHeight != cmos->height || optics->srcWidth == 0 || optics->srcHeight == 0) {
        ctx->setError("Optics dst dimensions must match CMOS dimensions");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const size_t dstPixels = static_cast<size_t>(optics->dstWidth) * optics->dstHeight;
    const size_t bytesPerPx = (cmos->bitDepth <= 8) ? 1u : 2u;
    if (out_bayer_bytes < dstPixels * bytesPerPx) {
        ctx->setError("out_bayer_bytes too small");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    const VkDeviceSize srcBytes = static_cast<VkDeviceSize>(optics->srcWidth) * optics->srcHeight * 16ULL;
    if (ext_frame->desc.colorMemorySizeBytes < srcBytes) {
        ctx->setError("External frame colorMemorySizeBytes smaller than optics src buffer");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    return dispatch_optics_pipeline_internal(
        ctx, ext_frame->colorBuffer, srcBytes, filter, optics, cmos, nullptr, out_bayer, out_gpu_ms);
}

FUJI_VK_EXPORT void fuji_vk_release_external_frame(
    FujiVkContext* ctx,
    FujiVkExternalFrame* frame) {
    if (!ctx || !frame) return;
    if (ctx->device) {
        if (frame->colorBuffer && ctx->vk.destroyBuffer) ctx->vk.destroyBuffer(ctx->device, frame->colorBuffer, nullptr);
        if (frame->colorMemory && ctx->vk.freeMemory) ctx->vk.freeMemory(ctx->device, frame->colorMemory, nullptr);
        if (frame->depthBuffer && ctx->vk.destroyBuffer) ctx->vk.destroyBuffer(ctx->device, frame->depthBuffer, nullptr);
        if (frame->depthMemory && ctx->vk.freeMemory) ctx->vk.freeMemory(ctx->device, frame->depthMemory, nullptr);
    }
    delete frame;
}

} /* extern "C" */

/* Minimal internal bridge for companion Vulkan translation units (e.g. fuji_vulkan_scene3d.cpp, fuji_vulkan_custom_spv.cpp) */
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

bool fuji_vk_internal_get_device_view(FujiVkContext* ctx, FujiVkInternalDeviceView* out_view) {
    if (!ctx || !out_view || !ctx->device || !ctx->computeQueue || !ctx->cmdPool || !ctx->vk.getDeviceProcAddr) {
        return false;
    }
    out_view->device = ctx->device;
    out_view->physDev = ctx->physDev;
    out_view->computeQueue = ctx->computeQueue;
    out_view->queueFamily = ctx->queueFamily;
    out_view->cmdPool = ctx->cmdPool;
    out_view->memProps = &ctx->memProps;
    out_view->getDeviceProcAddr = ctx->vk.getDeviceProcAddr;
    out_view->pipelineCache = ctx->pipelineCache;
    return true;
}

void fuji_vk_internal_mark_pipeline_cache_dirty(FujiVkContext* ctx) {
    if (ctx) ctx->cacheDirty = true;
}

bool fuji_vk_internal_acquire_scene_device_buffer(FujiVkContext* ctx, VkDeviceSize bytes, VkBuffer* out_buf) {
    if (!ctx || !out_buf || bytes == 0ULL) return false;
    ctx->cachedOpticsValid = false;
    if (!ctx->ensureDeviceBuffer(
            ctx->bufSceneDevice,
            bytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
        ctx->cachedSceneValid = false;
        return false;
    }
    *out_buf = ctx->bufSceneDevice.buffer;
    return *out_buf != nullptr;
}

void fuji_vk_internal_commit_scene_device_buffer(FujiVkContext* ctx, uint64_t scene_key, VkDeviceSize bytes) {
    if (!ctx) return;
    ctx->cachedOpticsValid = false;
    ctx->cachedSceneKey = scene_key;
    ctx->cachedSceneBytes = bytes;
    ctx->cachedSceneValid = (scene_key != 0ULL && bytes > 0ULL && ctx->bufSceneDevice.buffer != nullptr);
}

bool fuji_vk_internal_get_scene_device_buffer(FujiVkContext* ctx, VkBuffer* out_buf, VkDeviceSize* out_bytes) {
    if (!ctx || !out_buf || !out_bytes || !ctx->cachedSceneValid || !ctx->bufSceneDevice.buffer) return false;
    *out_buf = ctx->bufSceneDevice.buffer;
    *out_bytes = ctx->cachedSceneBytes;
    return true;
}

void fuji_vk_internal_set_error(FujiVkContext* ctx, const char* msg) {
    if (ctx && msg) ctx->setError(msg);
}
