/*
 * FUJIFILM GFX100S Emulator - Vulkan 1.1 Compute ISP Statistics Parallel Reduction
 *
 * Self-contained C++17 compute runtime dynamically loading libvulkan.so.1 (Linux) or
 * vulkan-1.dll (Windows) with ZERO static Vulkan linkage and ZERO extra runtime DLLs.
 * Dispatches isp_stats_reduce.comp (16x16 workgroups with shared-memory local atomics)
 * returning a compact 9,280-byte FujiVkIspStatsOutput struct.
 */

#include "fuji_vulkan_stats.h"
#include "fuji_vulkan_optics.h"
#include "fuji_vk_abi.h"
#include "fuji_vulkan_stats_spirv.h"

#include <algorithm>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <vector>

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <windows.h>
#else
#  include <dlfcn.h>
#endif

static_assert(sizeof(FujiVkIspStatsParams) == 96, "FujiVkIspStatsParams must be 96 bytes");
static_assert(sizeof(FujiVkIspStatsOutput) == FUJI_VK_STATS_OUTPUT_BYTES, "FujiVkIspStatsOutput must be 9280 bytes");

namespace {

struct StatsDynLib {
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
    ~StatsDynLib() { close(); }
};

struct StatsBufAlloc {
    VkBuffer       buffer = nullptr;
    VkDeviceMemory memory = nullptr;
    VkDeviceSize   capacity = 0;
    void*          mapped = nullptr;
};

struct StatsVkTable {
    PFN_vkGetInstanceProcAddr                    getInstanceProcAddr = nullptr;
    PFN_vkGetDeviceProcAddr                      getDeviceProcAddr = nullptr;
    PFN_vkCreateInstance                         createInstance = nullptr;
    PFN_vkDestroyInstance                        destroyInstance = nullptr;
    PFN_vkEnumeratePhysicalDevices               enumeratePhysicalDevices = nullptr;
    PFN_vkGetPhysicalDeviceProperties            getPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties getPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties      getPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkCreateDevice                           createDevice = nullptr;
    PFN_vkDestroyDevice                          destroyDevice = nullptr;
    PFN_vkGetDeviceQueue                         getDeviceQueue = nullptr;
    PFN_vkCreateBuffer                           createBuffer = nullptr;
    PFN_vkDestroyBuffer                          destroyBuffer = nullptr;
    PFN_vkGetBufferMemoryRequirements            getBufferMemoryRequirements = nullptr;
    PFN_vkAllocateMemory                         allocateMemory = nullptr;
    PFN_vkFreeMemory                             freeMemory = nullptr;
    PFN_vkBindBufferMemory                       bindBufferMemory = nullptr;
    PFN_vkMapMemory                              mapMemory = nullptr;
    PFN_vkUnmapMemory                            unmapMemory = nullptr;
    PFN_vkCreateShaderModule                     createShaderModule = nullptr;
    PFN_vkDestroyShaderModule                    destroyShaderModule = nullptr;
    PFN_vkCreateDescriptorSetLayout              createDescriptorSetLayout = nullptr;
    PFN_vkDestroyDescriptorSetLayout             destroyDescriptorSetLayout = nullptr;
    PFN_vkCreatePipelineLayout                   createPipelineLayout = nullptr;
    PFN_vkDestroyPipelineLayout                  destroyPipelineLayout = nullptr;
    PFN_vkCreateComputePipelines                 createComputePipelines = nullptr;
    PFN_vkDestroyPipeline                        destroyPipeline = nullptr;
    PFN_vkCreateDescriptorPool                   createDescriptorPool = nullptr;
    PFN_vkDestroyDescriptorPool                  destroyDescriptorPool = nullptr;
    PFN_vkAllocateDescriptorSets                 allocateDescriptorSets = nullptr;
    PFN_vkUpdateDescriptorSets                   updateDescriptorSets = nullptr;
    PFN_vkCreateCommandPool                      createCommandPool = nullptr;
    PFN_vkDestroyCommandPool                     destroyCommandPool = nullptr;
    PFN_vkAllocateCommandBuffers                 allocateCommandBuffers = nullptr;
    PFN_vkResetCommandBuffer                     resetCommandBuffer = nullptr;
    PFN_vkBeginCommandBuffer                     beginCommandBuffer = nullptr;
    PFN_vkEndCommandBuffer                       endCommandBuffer = nullptr;
    PFN_vkCmdBindPipeline                        cmdBindPipeline = nullptr;
    PFN_vkCmdBindDescriptorSets                  cmdBindDescriptorSets = nullptr;
    PFN_vkCmdDispatch                            cmdDispatch = nullptr;
    PFN_vkCmdPipelineBarrier                     cmdPipelineBarrier = nullptr;
    PFN_vkCmdCopyBuffer                          cmdCopyBuffer = nullptr;
    PFN_vkCmdFillBuffer                          cmdFillBuffer = nullptr;
    PFN_vkCreateFence                            createFence = nullptr;
    PFN_vkDestroyFence                           destroyFence = nullptr;
    PFN_vkResetFences                            resetFences = nullptr;
    PFN_vkWaitForFences                          waitForFences = nullptr;
    PFN_vkQueueSubmit                            queueSubmit = nullptr;
    PFN_vkDeviceWaitIdle                         deviceWaitIdle = nullptr;
};

void write_stats_err(char* buf, size_t len, const char* fmt, ...) {
    if (!buf || len == 0) return;
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, len, fmt, ap);
    va_end(ap);
}

int score_stats_device(VkPhysicalDeviceType type, uint32_t select_flags) {
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

bool normalize_params(const FujiVkIspStatsParams* in, size_t input_bytes, FujiVkIspStatsParams& out, std::string& err) {
    if (!in || in->width < 2u || in->height < 2u) {
        err = "Invalid stats dimensions (width/height must be >= 2)";
        return false;
    }
    if (in->inputFormat > FUJI_VK_STATS_FMT_BAYER_RGGB_U32_12) {
        err = "Unsupported stats inputFormat";
        return false;
    }
    size_t bpp = (in->inputFormat == FUJI_VK_STATS_FMT_BAYER_RGGB_16LE) ? 2u
               : (in->inputFormat == FUJI_VK_STATS_FMT_BAYER_RGGB_8) ? 1u : 4u;
    size_t needed = static_cast<size_t>(in->width) * static_cast<size_t>(in->height) * bpp;
    if (input_bytes < needed) {
        err = "Input pixel buffer too small for declared dimensions/format";
        return false;
    }
    out = *in;
    if (out.flags == 0u) out.flags = FUJI_VK_STATS_FLAG_ALL;
    if (out.viewX >= out.width) out.viewX = 0u;
    if (out.viewY >= out.height) out.viewY = 0u;
    out.viewX &= ~1u;
    out.viewY &= ~1u;
    if (out.viewW == 0u || out.viewX + out.viewW > out.width) out.viewW = out.width - out.viewX;
    if (out.viewH == 0u || out.viewY + out.viewH > out.height) out.viewH = out.height - out.viewY;
    out.viewW &= ~1u;
    out.viewH &= ~1u;
    if (out.viewW < 2u || out.viewH < 2u) {
        err = "Active viewport too small (< 2x2)";
        return false;
    }
    if (out.gridCols == 0u || out.gridCols > 16u) out.gridCols = 16u;
    if (out.gridRows == 0u || out.gridRows > 16u) out.gridRows = 16u;
    if (out.stepQuad == 0u) {
        out.stepQuad = (out.viewW >= 512u && out.viewH >= 256u) ? 2u : 1u;
    }
    if (out.awbHighlightThresh10 == 0u) out.awbHighlightThresh10 = 960u;
    if (out.afRoiX1 == 0u || out.afRoiX1 > out.width) out.afRoiX1 = out.width;
    if (out.afRoiY1 == 0u || out.afRoiY1 > out.height) out.afRoiY1 = out.height;
    if (out.afCols == 0u || out.afCols > 13u) out.afCols = 13u;
    if (out.afRows == 0u || out.afRows > 9u) out.afRows = 9u;
    if (out.afStepQuad == 0u) out.afStepQuad = out.stepQuad;
    if (out.ob14 == 0u) out.ob14 = 1024u;
    return true;
}

inline uint32_t read_u32_le(const uint8_t* bytes, size_t byte_len, uint32_t word_idx) {
    size_t off = static_cast<size_t>(word_idx) * 4u;
    uint32_t v = 0u;
    for (size_t i = 0; i < 4u && (off + i) < byte_len; ++i) {
        v |= static_cast<uint32_t>(bytes[off + i]) << (i * 8u);
    }
    return v;
}

inline uint32_t cpu_read_sample10(const uint8_t* bytes, size_t byte_len, const FujiVkIspStatsParams& u, uint32_t x, uint32_t y) {
    uint32_t idx = y * u.width + x;
    if (u.inputFormat == FUJI_VK_STATS_FMT_BAYER_RGGB_16LE) {
        uint32_t w = read_u32_le(bytes, byte_len, idx >> 1u);
        uint32_t v16 = ((idx & 1u) != 0u) ? (w >> 16u) : (w & 0xFFFFu);
        return v16 >> 6u;
    } else if (u.inputFormat == FUJI_VK_STATS_FMT_BAYER_RGGB_8) {
        uint32_t w = read_u32_le(bytes, byte_len, idx >> 2u);
        uint32_t shift = (idx & 3u) << 3u;
        return ((w >> shift) & 0xFFu) << 2u;
    } else if (u.inputFormat == FUJI_VK_STATS_FMT_BAYER_RGGB_U32_12) {
        uint32_t w = read_u32_le(bytes, byte_len, idx);
        return (w & 0xFFFu) >> 2u;
    } else {
        uint32_t w = read_u32_le(bytes, byte_len, idx);
        uint32_t r8 = w & 0xFFu;
        uint32_t g8 = (w >> 8u) & 0xFFu;
        uint32_t b8 = (w >> 16u) & 0xFFu;
        uint32_t y8 = (77u * r8 + 150u * g8 + 29u * b8 + 128u) >> 8u;
        return std::min(y8, 255u) << 2u;
    }
}

} /* namespace */

struct FujiVkStatsContext {
    StatsDynLib                      loader;
    StatsVkTable                     vk{};
    std::string                      deviceName;
    std::string                      lastError;

    VkInstance                       instance = nullptr;
    VkPhysicalDevice                 physDev = nullptr;
    VkPhysicalDeviceMemoryProperties memProps{};
    VkDevice                         device = nullptr;
    VkQueue                          computeQueue = nullptr;
    uint32_t                         queueFamily = 0;

    VkCommandPool                    cmdPool = nullptr;
    VkCommandBuffer                  cmdBuf = nullptr;
    VkFence                          fence = nullptr;

    VkDescriptorSetLayout            descSetLayout = nullptr;
    VkPipelineLayout                 pipelineLayout = nullptr;
    VkDescriptorPool                 descPool = nullptr;
    VkDescriptorSet                  descSet = nullptr;

    VkShaderModule                   shaderModule = nullptr;
    VkPipeline                       pipeline = nullptr;

    StatsBufAlloc                    bufIn{};
    StatsBufAlloc                    bufInDevice{};
    StatsBufAlloc                    bufOutDevice{};
    StatsBufAlloc                    bufOut{};
    StatsBufAlloc                    bufUbo{};

    int32_t findMemoryType(uint32_t typeBits, VkFlags requiredProps) const {
        for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
            if ((typeBits & (1u << i)) != 0 &&
                (memProps.memoryTypes[i].propertyFlags & requiredProps) == requiredProps) {
                return static_cast<int32_t>(i);
            }
        }
        return -1;
    }

    void freeBuffer(StatsBufAlloc& a) {
        if (device) {
            if (a.mapped && a.memory && vk.unmapMemory) vk.unmapMemory(device, a.memory);
            if (a.buffer && vk.destroyBuffer) vk.destroyBuffer(device, a.buffer, nullptr);
            if (a.memory && vk.freeMemory) vk.freeMemory(device, a.memory, nullptr);
        }
        a = StatsBufAlloc{};
    }

    bool ensureHostBuffer(StatsBufAlloc& a, VkDeviceSize neededBytes, VkFlags usage) {
        if (neededBytes < 64) neededBytes = 64;
        if (a.buffer && a.mapped && a.capacity >= neededBytes) return true;
        freeBuffer(a);

        VkDeviceSize allocCap = std::max<VkDeviceSize>(neededBytes, 4096);
        VkBufferCreateInfo bci{};
        bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bci.size = allocCap;
        bci.usage = usage;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vk.createBuffer(device, &bci, nullptr, &a.buffer) != VK_SUCCESS) {
            lastError = "vkCreateBuffer failed";
            return false;
        }
        VkMemoryRequirements req{};
        vk.getBufferMemoryRequirements(device, a.buffer, &req);
        VkFlags wanted = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        int32_t memIdx = findMemoryType(req.memoryTypeBits, wanted | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
        if (memIdx < 0) memIdx = findMemoryType(req.memoryTypeBits, wanted);
        if (memIdx < 0) {
            freeBuffer(a);
            lastError = "No HOST_VISIBLE|HOST_COHERENT memory type";
            return false;
        }
        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = static_cast<uint32_t>(memIdx);
        if (vk.allocateMemory(device, &mai, nullptr, &a.memory) != VK_SUCCESS) {
            freeBuffer(a);
            lastError = "vkAllocateMemory failed";
            return false;
        }
        if (vk.bindBufferMemory(device, a.buffer, a.memory, 0) != VK_SUCCESS) {
            freeBuffer(a);
            lastError = "vkBindBufferMemory failed";
            return false;
        }
        if (vk.mapMemory(device, a.memory, 0, allocCap, 0, &a.mapped) != VK_SUCCESS) {
            freeBuffer(a);
            lastError = "vkMapMemory failed";
            return false;
        }
        a.capacity = allocCap;
        return true;
    }

    bool ensureDeviceBuffer(StatsBufAlloc& a, VkDeviceSize neededBytes, VkFlags usage) {
        if (neededBytes < 64) neededBytes = 64;
        if (a.buffer && a.capacity >= neededBytes) return true;
        freeBuffer(a);

        VkDeviceSize allocCap = std::max<VkDeviceSize>(neededBytes, 4096);
        VkBufferCreateInfo bci{};
        bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bci.size = allocCap;
        bci.usage = usage;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vk.createBuffer(device, &bci, nullptr, &a.buffer) != VK_SUCCESS) {
            lastError = "vkCreateBuffer (device) failed";
            return false;
        }
        VkMemoryRequirements req{};
        vk.getBufferMemoryRequirements(device, a.buffer, &req);
        int32_t memIdx = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (memIdx < 0) {
            memIdx = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        }
        if (memIdx < 0) {
            freeBuffer(a);
            lastError = "No DEVICE_LOCAL/HOST_VISIBLE memory type";
            return false;
        }
        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = static_cast<uint32_t>(memIdx);
        if (vk.allocateMemory(device, &mai, nullptr, &a.memory) != VK_SUCCESS) {
            freeBuffer(a);
            lastError = "vkAllocateMemory (device) failed";
            return false;
        }
        if (vk.bindBufferMemory(device, a.buffer, a.memory, 0) != VK_SUCCESS) {
            freeBuffer(a);
            lastError = "vkBindBufferMemory (device) failed";
            return false;
        }
        a.capacity = allocCap;
        return true;
    }

    ~FujiVkStatsContext() {
        if (device && vk.deviceWaitIdle) vk.deviceWaitIdle(device);
        freeBuffer(bufIn);
        freeBuffer(bufInDevice);
        freeBuffer(bufOutDevice);
        freeBuffer(bufOut);
        freeBuffer(bufUbo);
        if (device) {
            if (pipeline && vk.destroyPipeline) vk.destroyPipeline(device, pipeline, nullptr);
            if (shaderModule && vk.destroyShaderModule) vk.destroyShaderModule(device, shaderModule, nullptr);
            if (descPool && vk.destroyDescriptorPool) vk.destroyDescriptorPool(device, descPool, nullptr);
            if (pipelineLayout && vk.destroyPipelineLayout) vk.destroyPipelineLayout(device, pipelineLayout, nullptr);
            if (descSetLayout && vk.destroyDescriptorSetLayout) vk.destroyDescriptorSetLayout(device, descSetLayout, nullptr);
            if (fence && vk.destroyFence) vk.destroyFence(device, fence, nullptr);
            if (cmdPool && vk.destroyCommandPool) vk.destroyCommandPool(device, cmdPool, nullptr);
            if (vk.destroyDevice) vk.destroyDevice(device, nullptr);
        }
        if (instance && vk.destroyInstance) vk.destroyInstance(instance, nullptr);
    }
};

extern "C" {

FUJI_VK_STATS_EXPORT uint32_t fuji_vk_stats_abi_version(void) {
    return FUJI_VK_STATS_ABI_VERSION;
}

FUJI_VK_STATS_EXPORT int32_t fuji_vk_stats_get_spirv_words(
    const uint32_t** out_words,
    uint32_t* out_word_count
) {
    if (!out_words || !out_word_count) return FUJI_VK_ERR_INVALID_ARG;
    *out_words = kFujiSpirvIspStatsReduce;
    *out_word_count = static_cast<uint32_t>(sizeof(kFujiSpirvIspStatsReduce) / sizeof(uint32_t));
    return FUJI_VK_OK;
}

FUJI_VK_STATS_EXPORT int32_t fuji_vk_stats_create(
    uint32_t select_flags,
    FujiVkStatsContext** out_ctx,
    char* err_buf,
    size_t err_buf_len
) {
    if (!out_ctx) {
        write_stats_err(err_buf, err_buf_len, "out_ctx must not be NULL");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    *out_ctx = nullptr;
    if (select_flags == 0u) select_flags = FUJI_VK_SELECT_DEFAULT;

    auto ctx = std::unique_ptr<FujiVkStatsContext>(new (std::nothrow) FujiVkStatsContext());
    if (!ctx) {
        write_stats_err(err_buf, err_buf_len, "Out of host memory");
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

#if defined(_WIN32)
    const char* libName = "vulkan-1.dll";
#else
    const char* libName = "libvulkan.so.1";
#endif
    if (!ctx->loader.open(libName)) {
        write_stats_err(err_buf, err_buf_len, "Failed to load %s", libName);
        return FUJI_VK_ERR_NO_LOADER;
    }
    ctx->vk.getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(ctx->loader.sym("vkGetInstanceProcAddr"));
    if (!ctx->vk.getInstanceProcAddr) {
        write_stats_err(err_buf, err_buf_len, "Missing vkGetInstanceProcAddr in %s", libName);
        return FUJI_VK_ERR_NO_LOADER;
    }
    ctx->vk.createInstance = reinterpret_cast<PFN_vkCreateInstance>(ctx->vk.getInstanceProcAddr(nullptr, "vkCreateInstance"));
    if (!ctx->vk.createInstance) {
        write_stats_err(err_buf, err_buf_len, "Missing vkCreateInstance");
        return FUJI_VK_ERR_NO_LOADER;
    }

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "fuji-isp-stats";
    appInfo.applicationVersion = 1;
    appInfo.pEngineName = "fuji-vulkan-stats";
    appInfo.engineVersion = 1;
    appInfo.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &appInfo;
    if (ctx->vk.createInstance(&ici, nullptr, &ctx->instance) != VK_SUCCESS || !ctx->instance) {
        write_stats_err(err_buf, err_buf_len, "vkCreateInstance failed");
        return FUJI_VK_ERR_INIT_FAILED;
    }

#define LOAD_INST(field, symName) \
    ctx->vk.field = reinterpret_cast<decltype(ctx->vk.field)>(ctx->vk.getInstanceProcAddr(ctx->instance, symName)); \
    if (!ctx->vk.field) { write_stats_err(err_buf, err_buf_len, "Missing instance symbol %s", symName); return FUJI_VK_ERR_INIT_FAILED; }

    LOAD_INST(destroyInstance, "vkDestroyInstance");
    LOAD_INST(enumeratePhysicalDevices, "vkEnumeratePhysicalDevices");
    LOAD_INST(getPhysicalDeviceProperties, "vkGetPhysicalDeviceProperties");
    LOAD_INST(getPhysicalDeviceQueueFamilyProperties, "vkGetPhysicalDeviceQueueFamilyProperties");
    LOAD_INST(getPhysicalDeviceMemoryProperties, "vkGetPhysicalDeviceMemoryProperties");
    LOAD_INST(createDevice, "vkCreateDevice");
    LOAD_INST(getDeviceProcAddr, "vkGetDeviceProcAddr");
#undef LOAD_INST

    uint32_t devCount = 0;
    if (ctx->vk.enumeratePhysicalDevices(ctx->instance, &devCount, nullptr) != VK_SUCCESS || devCount == 0) {
        write_stats_err(err_buf, err_buf_len, "No Vulkan physical devices found");
        return FUJI_VK_ERR_NO_DEVICE;
    }
    std::vector<VkPhysicalDevice> devs(devCount);
    ctx->vk.enumeratePhysicalDevices(ctx->instance, &devCount, devs.data());

    int bestScore = -1;
    VkPhysicalDevice bestDev = nullptr;
    uint32_t bestQf = 0;
    VkPhysicalDeviceProperties bestProps{};

    for (uint32_t i = 0; i < devCount; ++i) {
        VkPhysicalDeviceProperties props{};
        ctx->vk.getPhysicalDeviceProperties(devs[i], &props);
        int sc = score_stats_device(props.deviceType, select_flags);
        if (sc < 0) continue;

        uint32_t qfCount = 0;
        ctx->vk.getPhysicalDeviceQueueFamilyProperties(devs[i], &qfCount, nullptr);
        if (qfCount == 0) continue;
        std::vector<VkQueueFamilyProperties> qfs(qfCount);
        ctx->vk.getPhysicalDeviceQueueFamilyProperties(devs[i], &qfCount, qfs.data());
        int foundQf = -1;
        for (uint32_t q = 0; q < qfCount; ++q) {
            if ((qfs[q].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
                foundQf = static_cast<int>(q);
                break;
            }
        }
        if (foundQf < 0) continue;
        if (sc > bestScore) {
            bestScore = sc;
            bestDev = devs[i];
            bestQf = static_cast<uint32_t>(foundQf);
            bestProps = props;
        }
    }
    if (!bestDev) {
        write_stats_err(err_buf, err_buf_len, "No eligible Vulkan compute device found (flags=0x%x)", select_flags);
        return FUJI_VK_ERR_NO_DEVICE;
    }

    ctx->physDev = bestDev;
    ctx->queueFamily = bestQf;
    ctx->deviceName = bestProps.deviceName;
    ctx->vk.getPhysicalDeviceMemoryProperties(bestDev, &ctx->memProps);

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{};
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = bestQf;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;

    VkDeviceCreateInfo dci{};
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    if (ctx->vk.createDevice(bestDev, &dci, nullptr, &ctx->device) != VK_SUCCESS || !ctx->device) {
        write_stats_err(err_buf, err_buf_len, "vkCreateDevice failed");
        return FUJI_VK_ERR_INIT_FAILED;
    }

#define LOAD_DEV(field, symName) \
    ctx->vk.field = reinterpret_cast<decltype(ctx->vk.field)>(ctx->vk.getDeviceProcAddr(ctx->device, symName)); \
    if (!ctx->vk.field) { write_stats_err(err_buf, err_buf_len, "Missing device symbol %s", symName); return FUJI_VK_ERR_INIT_FAILED; }

    LOAD_DEV(destroyDevice, "vkDestroyDevice");
    LOAD_DEV(getDeviceQueue, "vkGetDeviceQueue");
    LOAD_DEV(createBuffer, "vkCreateBuffer");
    LOAD_DEV(destroyBuffer, "vkDestroyBuffer");
    LOAD_DEV(getBufferMemoryRequirements, "vkGetBufferMemoryRequirements");
    LOAD_DEV(allocateMemory, "vkAllocateMemory");
    LOAD_DEV(freeMemory, "vkFreeMemory");
    LOAD_DEV(bindBufferMemory, "vkBindBufferMemory");
    LOAD_DEV(mapMemory, "vkMapMemory");
    LOAD_DEV(unmapMemory, "vkUnmapMemory");
    LOAD_DEV(createShaderModule, "vkCreateShaderModule");
    LOAD_DEV(destroyShaderModule, "vkDestroyShaderModule");
    LOAD_DEV(createDescriptorSetLayout, "vkCreateDescriptorSetLayout");
    LOAD_DEV(destroyDescriptorSetLayout, "vkDestroyDescriptorSetLayout");
    LOAD_DEV(createPipelineLayout, "vkCreatePipelineLayout");
    LOAD_DEV(destroyPipelineLayout, "vkDestroyPipelineLayout");
    LOAD_DEV(createComputePipelines, "vkCreateComputePipelines");
    LOAD_DEV(destroyPipeline, "vkDestroyPipeline");
    LOAD_DEV(createDescriptorPool, "vkCreateDescriptorPool");
    LOAD_DEV(destroyDescriptorPool, "vkDestroyDescriptorPool");
    LOAD_DEV(allocateDescriptorSets, "vkAllocateDescriptorSets");
    LOAD_DEV(updateDescriptorSets, "vkUpdateDescriptorSets");
    LOAD_DEV(createCommandPool, "vkCreateCommandPool");
    LOAD_DEV(destroyCommandPool, "vkDestroyCommandPool");
    LOAD_DEV(allocateCommandBuffers, "vkAllocateCommandBuffers");
    LOAD_DEV(resetCommandBuffer, "vkResetCommandBuffer");
    LOAD_DEV(beginCommandBuffer, "vkBeginCommandBuffer");
    LOAD_DEV(endCommandBuffer, "vkEndCommandBuffer");
    LOAD_DEV(cmdBindPipeline, "vkCmdBindPipeline");
    LOAD_DEV(cmdBindDescriptorSets, "vkCmdBindDescriptorSets");
    LOAD_DEV(cmdDispatch, "vkCmdDispatch");
    LOAD_DEV(cmdPipelineBarrier, "vkCmdPipelineBarrier");
    LOAD_DEV(cmdCopyBuffer, "vkCmdCopyBuffer");
    LOAD_DEV(cmdFillBuffer, "vkCmdFillBuffer");
    LOAD_DEV(createFence, "vkCreateFence");
    LOAD_DEV(destroyFence, "vkDestroyFence");
    LOAD_DEV(resetFences, "vkResetFences");
    LOAD_DEV(waitForFences, "vkWaitForFences");
    LOAD_DEV(queueSubmit, "vkQueueSubmit");
    LOAD_DEV(deviceWaitIdle, "vkDeviceWaitIdle");
#undef LOAD_DEV

    ctx->vk.getDeviceQueue(ctx->device, bestQf, 0, &ctx->computeQueue);

    VkCommandPoolCreateInfo cpci{};
    cpci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cpci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cpci.queueFamilyIndex = bestQf;
    if (ctx->vk.createCommandPool(ctx->device, &cpci, nullptr, &ctx->cmdPool) != VK_SUCCESS) {
        write_stats_err(err_buf, err_buf_len, "vkCreateCommandPool failed");
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkCommandBufferAllocateInfo cbai{};
    cbai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbai.commandPool = ctx->cmdPool;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = 1;
    if (ctx->vk.allocateCommandBuffers(ctx->device, &cbai, &ctx->cmdBuf) != VK_SUCCESS) {
        write_stats_err(err_buf, err_buf_len, "vkAllocateCommandBuffers failed");
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkFenceCreateInfo fci{};
    fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (ctx->vk.createFence(ctx->device, &fci, nullptr, &ctx->fence) != VK_SUCCESS) {
        write_stats_err(err_buf, err_buf_len, "vkCreateFence failed");
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo dslci{};
    dslci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslci.bindingCount = 3;
    dslci.pBindings = bindings;
    if (ctx->vk.createDescriptorSetLayout(ctx->device, &dslci, nullptr, &ctx->descSetLayout) != VK_SUCCESS) {
        write_stats_err(err_buf, err_buf_len, "vkCreateDescriptorSetLayout failed");
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkPipelineLayoutCreateInfo plci{};
    plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plci.setLayoutCount = 1;
    plci.pSetLayouts = &ctx->descSetLayout;
    if (ctx->vk.createPipelineLayout(ctx->device, &plci, nullptr, &ctx->pipelineLayout) != VK_SUCCESS) {
        write_stats_err(err_buf, err_buf_len, "vkCreatePipelineLayout failed");
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 2;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    poolSizes[1].descriptorCount = 1;

    VkDescriptorPoolCreateInfo dpci{};
    dpci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpci.maxSets = 1;
    dpci.poolSizeCount = 2;
    dpci.pPoolSizes = poolSizes;
    if (ctx->vk.createDescriptorPool(ctx->device, &dpci, nullptr, &ctx->descPool) != VK_SUCCESS) {
        write_stats_err(err_buf, err_buf_len, "vkCreateDescriptorPool failed");
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkDescriptorSetAllocateInfo dsai{};
    dsai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsai.descriptorPool = ctx->descPool;
    dsai.descriptorSetCount = 1;
    dsai.pSetLayouts = &ctx->descSetLayout;
    if (ctx->vk.allocateDescriptorSets(ctx->device, &dsai, &ctx->descSet) != VK_SUCCESS) {
        write_stats_err(err_buf, err_buf_len, "vkAllocateDescriptorSets failed");
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkShaderModuleCreateInfo smci{};
    smci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smci.codeSize = sizeof(kFujiSpirvIspStatsReduce);
    smci.pCode = kFujiSpirvIspStatsReduce;
    if (ctx->vk.createShaderModule(ctx->device, &smci, nullptr, &ctx->shaderModule) != VK_SUCCESS) {
        write_stats_err(err_buf, err_buf_len, "vkCreateShaderModule(isp_stats_reduce) failed");
        return FUJI_VK_ERR_INIT_FAILED;
    }

    VkComputePipelineCreateInfo pci{};
    pci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pci.stage.module = ctx->shaderModule;
    pci.stage.pName = "main";
    pci.layout = ctx->pipelineLayout;
    if (ctx->vk.createComputePipelines(ctx->device, nullptr, 1, &pci, nullptr, &ctx->pipeline) != VK_SUCCESS) {
        write_stats_err(err_buf, err_buf_len, "vkCreateComputePipelines(isp_stats_reduce) failed");
        return FUJI_VK_ERR_INIT_FAILED;
    }

    if (!ctx->ensureDeviceBuffer(
            ctx->bufOutDevice,
            FUJI_VK_STATS_OUTPUT_BYTES,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT) ||
        !ctx->ensureHostBuffer(ctx->bufOut, FUJI_VK_STATS_OUTPUT_BYTES, VK_BUFFER_USAGE_TRANSFER_DST_BIT) ||
        !ctx->ensureHostBuffer(ctx->bufUbo, sizeof(FujiVkIspStatsParams), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)) {
        write_stats_err(err_buf, err_buf_len, "%s", ctx->lastError.c_str());
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    *out_ctx = ctx.release();
    return FUJI_VK_OK;
}

FUJI_VK_STATS_EXPORT void fuji_vk_stats_destroy(FujiVkStatsContext* ctx) {
    delete ctx;
}

FUJI_VK_STATS_EXPORT const char* fuji_vk_stats_last_error(const FujiVkStatsContext* ctx) {
    return ctx ? ctx->lastError.c_str() : "NULL stats context";
}

FUJI_VK_STATS_EXPORT const char* fuji_vk_stats_device_name(const FujiVkStatsContext* ctx) {
    return ctx ? ctx->deviceName.c_str() : "";
}

FUJI_VK_STATS_EXPORT int32_t fuji_vk_stats_reduce(
    FujiVkStatsContext* ctx,
    const uint8_t* input_bytes,
    size_t input_byte_count,
    const FujiVkIspStatsParams* params,
    FujiVkIspStatsOutput* out_stats,
    double* out_gpu_ms
) {
    if (!ctx || !input_bytes || !params || !out_stats) return FUJI_VK_ERR_INVALID_ARG;
    FujiVkIspStatsParams u{};
    if (!normalize_params(params, input_byte_count, u, ctx->lastError)) {
        return FUJI_VK_ERR_INVALID_ARG;
    }

    size_t paddedInBytes = ((input_byte_count + 3u) & ~static_cast<size_t>(3u));
    if (!ctx->ensureHostBuffer(ctx->bufIn, paddedInBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT) ||
        !ctx->ensureDeviceBuffer(ctx->bufInDevice, paddedInBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    auto t0 = std::chrono::steady_clock::now();

    /* Upload pixel buffer to staging + uniform params; reduction runs 100% in DEVICE_LOCAL VRAM (zero PCIe atomics!) */
    std::memcpy(ctx->bufIn.mapped, input_bytes, input_byte_count);
    if (paddedInBytes > input_byte_count) {
        std::memset(static_cast<uint8_t*>(ctx->bufIn.mapped) + input_byte_count, 0, paddedInBytes - input_byte_count);
    }
    std::memcpy(ctx->bufUbo.mapped, &u, sizeof(u));

    VkDescriptorBufferInfo biIn{ctx->bufInDevice.buffer, 0, paddedInBytes};
    VkDescriptorBufferInfo biOut{ctx->bufOutDevice.buffer, 0, FUJI_VK_STATS_OUTPUT_BYTES};
    VkDescriptorBufferInfo biUbo{ctx->bufUbo.buffer, 0, sizeof(u)};

    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = ctx->descSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &biIn;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = ctx->descSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &biOut;

    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = ctx->descSet;
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    writes[2].pBufferInfo = &biUbo;

    ctx->vk.updateDescriptorSets(ctx->device, 3, writes, 0, nullptr);

    ctx->vk.resetCommandBuffer(ctx->cmdBuf, 0);
    VkCommandBufferBeginInfo cbbi{};
    cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    cbbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (ctx->vk.beginCommandBuffer(ctx->cmdBuf, &cbbi) != VK_SUCCESS) {
        ctx->lastError = "vkBeginCommandBuffer failed";
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    VkBufferCopy inCopy{};
    inCopy.srcOffset = 0;
    inCopy.dstOffset = 0;
    inCopy.size = paddedInBytes;
    ctx->vk.cmdCopyBuffer(ctx->cmdBuf, ctx->bufIn.buffer, ctx->bufInDevice.buffer, 1, &inCopy);
    ctx->vk.cmdFillBuffer(ctx->cmdBuf, ctx->bufOutDevice.buffer, 0, FUJI_VK_STATS_OUTPUT_BYTES, 0u);

    VkMemoryBarrier xferToComp{};
    xferToComp.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    xferToComp.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    xferToComp.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    ctx->vk.cmdPipelineBarrier(ctx->cmdBuf, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &xferToComp, 0, nullptr, 0, nullptr);

    uint32_t stepPx = std::max(u.stepQuad, 1u) << 1u;
    uint32_t quadsX = (u.viewW + stepPx - 1u) / stepPx;
    uint32_t quadsY = (u.viewH + stepPx - 1u) / stepPx;
    uint32_t groupsX = (quadsX + 15u) / 16u;
    uint32_t groupsY = (quadsY + 15u) / 16u;

    ctx->vk.cmdBindPipeline(ctx->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeline);
    ctx->vk.cmdBindDescriptorSets(ctx->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipelineLayout, 0, 1, &ctx->descSet, 0, nullptr);
    ctx->vk.cmdDispatch(ctx->cmdBuf, std::max(groupsX, 1u), std::max(groupsY, 1u), 1u);

    VkMemoryBarrier compToXfer{};
    compToXfer.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    compToXfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    compToXfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    ctx->vk.cmdPipelineBarrier(ctx->cmdBuf, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &compToXfer, 0, nullptr, 0, nullptr);

    VkBufferCopy outCopy{};
    outCopy.srcOffset = 0;
    outCopy.dstOffset = 0;
    outCopy.size = FUJI_VK_STATS_OUTPUT_BYTES;
    ctx->vk.cmdCopyBuffer(ctx->cmdBuf, ctx->bufOutDevice.buffer, ctx->bufOut.buffer, 1, &outCopy);

    if (ctx->vk.endCommandBuffer(ctx->cmdBuf) != VK_SUCCESS) {
        ctx->lastError = "vkEndCommandBuffer failed";
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    ctx->vk.resetFences(ctx->device, 1, &ctx->fence);
    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &ctx->cmdBuf;
    if (ctx->vk.queueSubmit(ctx->computeQueue, 1, &si, ctx->fence) != VK_SUCCESS) {
        ctx->lastError = "vkQueueSubmit failed";
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }
    if (ctx->vk.waitForFences(ctx->device, 1, &ctx->fence, VK_TRUE, 10000000000ULL) != VK_SUCCESS) {
        ctx->lastError = "vkWaitForFences failed";
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    std::memcpy(out_stats, ctx->bufOut.mapped, FUJI_VK_STATS_OUTPUT_BYTES);

    if (out_gpu_ms) {
        auto t1 = std::chrono::steady_clock::now();
        *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    }
    return FUJI_VK_OK;
}

FUJI_VK_STATS_EXPORT int32_t fuji_vk_stats_reduce_cpu_ref(
    const uint8_t* input_bytes,
    size_t input_byte_count,
    const FujiVkIspStatsParams* params,
    FujiVkIspStatsOutput* out_stats
) {
    if (!input_bytes || !params || !out_stats) return FUJI_VK_ERR_INVALID_ARG;
    FujiVkIspStatsParams u{};
    std::string err;
    if (!normalize_params(params, input_byte_count, u, err)) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    std::memset(out_stats, 0, sizeof(*out_stats));

    uint32_t stepPx = std::max(u.stepQuad, 1u) << 1u;
    uint32_t endX = u.viewX + u.viewW;
    uint32_t endY = u.viewY + u.viewH;

    for (uint32_t py = u.viewY; py + 1u < endY; py += stepPx) {
        for (uint32_t px = u.viewX; px + 1u < endX; px += stepPx) {
            uint32_t r10, g1_10, g2_10, b10;
            uint32_t r8, g8, b8;
            if (u.inputFormat == FUJI_VK_STATS_FMT_PACKED_RGBA8) {
                uint32_t p = read_u32_le(input_bytes, input_byte_count, py * u.width + px);
                r8 = p & 0xFFu;
                g8 = (p >> 8u) & 0xFFu;
                b8 = (p >> 16u) & 0xFFu;
                r10 = r8 << 2u;
                g1_10 = g8 << 2u;
                g2_10 = g1_10;
                b10 = b8 << 2u;
            } else {
                r10 = cpu_read_sample10(input_bytes, input_byte_count, u, px, py);
                g1_10 = cpu_read_sample10(input_bytes, input_byte_count, u, px + 1u, py);
                g2_10 = cpu_read_sample10(input_bytes, input_byte_count, u, px, py + 1u);
                b10 = cpu_read_sample10(input_bytes, input_byte_count, u, px + 1u, py + 1u);
                r8 = r10 >> 2u;
                g8 = (g1_10 + g2_10 + 1u) >> 3u;
                b8 = b10 >> 2u;
            }
            uint32_t y8 = std::min((77u * r8 + 150u * g8 + 29u * b8 + 128u) >> 8u, 255u);
            uint32_t gAvg10 = (g1_10 + g2_10 + 1u) >> 1u;

            if ((u.flags & FUJI_VK_STATS_FLAG_AE_GRID) != 0u) {
                uint32_t gx = std::min(((px - u.viewX) * u.gridCols) / std::max(u.viewW, 1u), u.gridCols - 1u);
                uint32_t gy = std::min(((py - u.viewY) * u.gridRows) / std::max(u.viewH, 1u), u.gridRows - 1u);
                uint32_t cell = gy * u.gridCols + gx;
                out_stats->aeSumR[cell] += r10;
                out_stats->aeSumG1[cell] += g1_10;
                out_stats->aeSumB[cell] += b10;
                out_stats->aeSumG2[cell] += g2_10;
                out_stats->aeCount[cell] += 1u;
            }

            if ((u.flags & FUJI_VK_STATS_FLAG_HIST) != 0u) {
                out_stats->histY[y8] += 1u;
                out_stats->histR[r8] += 1u;
                out_stats->histG[g8] += 1u;
                out_stats->histB[b8] += 1u;
                out_stats->totalHistPixels += 1u;
            }

            out_stats->awbGlobalSumR += r10;
            out_stats->awbGlobalSumG += gAvg10;
            out_stats->awbGlobalSumB += b10;
            out_stats->awbGlobalCount += 1u;
            uint32_t maxCh = std::max(std::max(r10, b10), std::max(g1_10, g2_10));
            if (maxCh < u.awbHighlightThresh10) {
                out_stats->awbValidSumR += r10;
                out_stats->awbValidSumG += gAvg10;
                out_stats->awbValidSumB += b10;
                out_stats->awbValidCount += 1u;
            }

            if ((u.flags & FUJI_VK_STATS_FLAG_CDAF) != 0u &&
                px >= u.afRoiX0 && px < u.afRoiX1 &&
                py >= u.afRoiY0 && py < u.afRoiY1) {
                uint32_t gxSample = (u.inputFormat == FUJI_VK_STATS_FMT_PACKED_RGBA8) ? px : (px + 1u);
                uint32_t gC = (u.inputFormat == FUJI_VK_STATS_FMT_PACKED_RGBA8) ? (y8 << 2u) : g1_10;
                uint32_t gC14 = gC << 4u;
                if (gC14 > out_stats->cdafRoiMaxG14) out_stats->cdafRoiMaxG14 = gC14;
                out_stats->cdafRoiSumG10 += gC;
                out_stats->cdafRoiCount += 1u;

                if (gxSample + 2u < u.width) {
                    uint32_t gR1 = cpu_read_sample10(input_bytes, input_byte_count, u, gxSample + 2u, py);
                    int32_t dH1 = std::abs(static_cast<int32_t>(gR1) - static_cast<int32_t>(gC));
                    out_stats->cdafHorizTenengrad += static_cast<uint32_t>(dH1 * dH1) >> 2u;
                    if (gxSample + 4u < u.width) {
                        uint32_t gR2 = cpu_read_sample10(input_bytes, input_byte_count, u, gxSample + 4u, py);
                        int32_t lapH = std::abs(2 * static_cast<int32_t>(gR1) - static_cast<int32_t>(gC) - static_cast<int32_t>(gR2));
                        out_stats->cdafHorizLaplacian += static_cast<uint32_t>(lapH * lapH) >> 1u;
                    }
                }
                if (py + 2u < u.height) {
                    uint32_t gD1 = cpu_read_sample10(input_bytes, input_byte_count, u, gxSample, py + 2u);
                    int32_t dV1 = std::abs(static_cast<int32_t>(gD1) - static_cast<int32_t>(gC));
                    out_stats->cdafVertTenengrad += static_cast<uint32_t>(dV1 * dV1) >> 2u;
                    if (py + 4u < u.height) {
                        uint32_t gD2 = cpu_read_sample10(input_bytes, input_byte_count, u, gxSample, py + 4u);
                        int32_t lapV = std::abs(2 * static_cast<int32_t>(gD1) - static_cast<int32_t>(gC) - static_cast<int32_t>(gD2));
                        out_stats->cdafVertLaplacian += static_cast<uint32_t>(lapV * lapV) >> 1u;
                    }
                }
            }
        }
    }
    return FUJI_VK_OK;
}

} /* extern "C" */
