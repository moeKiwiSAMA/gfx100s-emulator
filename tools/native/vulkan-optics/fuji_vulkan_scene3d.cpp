/*
 * FUJIFILM GFX100S Emulator - Vulkan 1.1 Compute 3D Scene Rasterizer & Raytracer
 *
 * Shares the active VkDevice / VkQueue / VkCommandPool from FujiVkContext via a minimal
 * internal bridge (zero struct layout duplication, zero static Vulkan linkage) and renders
 * dynamic 3D scenes straight into `ctx->bufSceneDevice` (DEVICE_LOCAL VRAM) so the downstream
 * Vulkan optical + CMOS + ISP pipeline consumes them with ZERO PCIe upload.
 */

#include "fuji_vulkan_scene3d.h"
#include "fuji_vk_abi.h"
#include "fuji_vulkan_scene3d_spirv.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

static_assert(sizeof(FujiVkScene3dMilkFrogParams) == 448, "FujiVkScene3dMilkFrogParams must be 448 bytes");
static_assert(sizeof(FujiVkScene3dQrBallParams) == 96, "FujiVkScene3dQrBallParams must be 96 bytes");
static_assert(sizeof(FujiVkScene3dMonaLisaParams) == 96, "FujiVkScene3dMonaLisaParams must be 96 bytes");
static_assert(sizeof(FujiVkScene3dLayeredParams) == 80, "FujiVkScene3dLayeredParams must be 80 bytes");

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
extern bool fuji_vk_internal_acquire_scene_device_buffer(FujiVkContext* ctx, VkDeviceSize bytes, VkBuffer* out_buf);
extern void fuji_vk_internal_commit_scene_device_buffer(FujiVkContext* ctx, uint64_t scene_key, VkDeviceSize bytes);
extern bool fuji_vk_internal_get_scene_device_buffer(FujiVkContext* ctx, VkBuffer* out_buf, VkDeviceSize* out_bytes);
extern void fuji_vk_internal_set_error(FujiVkContext* ctx, const char* msg);

namespace {

typedef void (VKAPI_PTR *PFN_vkCmdFillBuffer)(VkCommandBuffer, VkBuffer, VkDeviceSize, VkDeviceSize, uint32_t);

struct S3dBuf {
    VkBuffer       buffer = nullptr;
    VkDeviceMemory memory = nullptr;
    VkDeviceSize   capacity = 0;
    void*          mapped = nullptr;
};

struct S3dVk {
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
    PFN_vkCmdDispatch                 cmdDispatch = nullptr;
    PFN_vkCmdPipelineBarrier          cmdPipelineBarrier = nullptr;
    PFN_vkCmdCopyBuffer               cmdCopyBuffer = nullptr;
    PFN_vkCmdFillBuffer               cmdFillBuffer = nullptr;
    PFN_vkCreateFence                 createFence = nullptr;
    PFN_vkDestroyFence                destroyFence = nullptr;
    PFN_vkResetFences                 resetFences = nullptr;
    PFN_vkWaitForFences               waitForFences = nullptr;
    PFN_vkQueueSubmit                 queueSubmit = nullptr;
    PFN_vkDeviceWaitIdle              deviceWaitIdle = nullptr;
};

struct Scene3dState {
    static constexpr uint32_t kNumDescSets = 4u; /* 0=pass0, 1=pass1, 2=pass2, 3=single */

    FujiVkInternalDeviceView view{};
    S3dVk                    vk{};
    bool                     initialized = false;

    VkCommandBuffer       cmdBuf = nullptr;
    VkFence               fence = nullptr;
    VkDescriptorSetLayout descSetLayout = nullptr;
    VkPipelineLayout      pipelineLayout = nullptr;
    VkDescriptorPool      descPool = nullptr;
    VkDescriptorSet       descSets[kNumDescSets] = {};
    VkShaderModule        shaderModules[FUJI_VK_SCENE3D_SHADER_COUNT] = {};
    VkPipeline            pipelines[FUJI_VK_SCENE3D_SHADER_COUNT] = {};

    S3dBuf bufDummy{};
    S3dBuf bufUbo{};
    S3dBuf bufStaging{};
    S3dBuf bufDepthGrid{};
    S3dBuf bufReadback{};

    /* Milk Frog GPU-resident resources */
    S3dBuf   bufMeshVerts{};
    S3dBuf   bufMeshIndices{};
    S3dBuf   bufStaticStage{};
    S3dBuf   bufXformVerts{};
    S3dBuf   bufScreenTris{};
    S3dBuf   bufTileCounts{};
    S3dBuf   bufTileTris{};
    uint64_t cachedMeshKey = 0ULL;
    uint32_t cachedMeshVerts = 0u;
    uint32_t cachedMeshTris = 0u;
    uint64_t cachedStageKey = 0ULL;
    uint32_t cachedStageW = 0u;
    uint32_t cachedStageH = 0u;

    /* QR-Ball GPU-resident matrix */
    S3dBuf   bufQrMatrix{};
    uint64_t cachedQrKey = 0ULL;

    /* Mona Lisa GPU-resident portrait */
    S3dBuf   bufMonaFg{};
    S3dBuf   bufMonaBg{};
    uint64_t cachedMonaKey = 0ULL;
    uint32_t cachedMonaW = 0u;
    uint32_t cachedMonaH = 0u;

    /* Layered studio GPU-resident base */
    S3dBuf   bufLayeredBase{};
    uint64_t cachedLayeredKey = 0ULL;
    uint32_t cachedLayeredW = 0u;
    uint32_t cachedLayeredH = 0u;

    int32_t findMemoryType(uint32_t typeBits, VkFlags req) const {
        if (!view.memProps) return -1;
        for (uint32_t i = 0; i < view.memProps->memoryTypeCount; ++i) {
            if ((typeBits & (1u << i)) != 0 &&
                (view.memProps->memoryTypes[i].propertyFlags & req) == req) {
                return static_cast<int32_t>(i);
            }
        }
        return -1;
    }

    void freeBuf(S3dBuf& a) {
        if (view.device) {
            if (a.mapped && a.memory && vk.unmapMemory) vk.unmapMemory(view.device, a.memory);
            if (a.buffer && vk.destroyBuffer) vk.destroyBuffer(view.device, a.buffer, nullptr);
            if (a.memory && vk.freeMemory) vk.freeMemory(view.device, a.memory, nullptr);
        }
        a = S3dBuf{};
    }

    bool ensureHostBuf(S3dBuf& a, VkDeviceSize needed, VkFlags usage) {
        if (needed == 0) needed = 64;
        if (a.buffer && a.mapped && a.capacity >= needed) return true;
        freeBuf(a);
        VkDeviceSize cap = std::max<VkDeviceSize>(needed, 4096);
        VkBufferCreateInfo bci{};
        bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bci.size = cap;
        bci.usage = usage;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vk.createBuffer(view.device, &bci, nullptr, &a.buffer) != VK_SUCCESS) return false;
        VkMemoryRequirements req{};
        vk.getBufferMemoryRequirements(view.device, a.buffer, &req);
        int32_t m = findMemoryType(
            req.memoryTypeBits,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (m < 0) {
            m = findMemoryType(
                req.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        }
        if (m < 0) { freeBuf(a); return false; }
        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = static_cast<uint32_t>(m);
        if (vk.allocateMemory(view.device, &mai, nullptr, &a.memory) != VK_SUCCESS) { freeBuf(a); return false; }
        if (vk.bindBufferMemory(view.device, a.buffer, a.memory, 0) != VK_SUCCESS) { freeBuf(a); return false; }
        if (vk.mapMemory(view.device, a.memory, 0, VK_WHOLE_SIZE, 0, &a.mapped) != VK_SUCCESS || !a.mapped) {
            freeBuf(a);
            return false;
        }
        a.capacity = cap;
        return true;
    }

    bool ensureDeviceBuf(S3dBuf& a, VkDeviceSize needed, VkFlags usage) {
        if (needed == 0) needed = 64;
        if (a.buffer && a.capacity >= needed) return true;
        freeBuf(a);
        VkDeviceSize cap = std::max<VkDeviceSize>(needed, 4096);
        VkBufferCreateInfo bci{};
        bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bci.size = cap;
        bci.usage = usage;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vk.createBuffer(view.device, &bci, nullptr, &a.buffer) != VK_SUCCESS) return false;
        VkMemoryRequirements req{};
        vk.getBufferMemoryRequirements(view.device, a.buffer, &req);
        int32_t m = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (m < 0) {
            m = findMemoryType(
                req.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        }
        if (m < 0) { freeBuf(a); return false; }
        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = static_cast<uint32_t>(m);
        if (vk.allocateMemory(view.device, &mai, nullptr, &a.memory) != VK_SUCCESS) { freeBuf(a); return false; }
        if (vk.bindBufferMemory(view.device, a.buffer, a.memory, 0) != VK_SUCCESS) { freeBuf(a); return false; }
        a.mapped = nullptr;
        a.capacity = cap;
        return true;
    }

    bool beginCmd() {
        vk.resetCommandBuffer(cmdBuf, 0);
        VkCommandBufferBeginInfo cbbi{};
        cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        cbbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        return vk.beginCommandBuffer(cmdBuf, &cbbi) == VK_SUCCESS;
    }

    bool submitAndWait() {
        if (vk.endCommandBuffer(cmdBuf) != VK_SUCCESS) return false;
        vk.resetFences(view.device, 1, &fence);
        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmdBuf;
        if (vk.queueSubmit(view.computeQueue, 1, &si, fence) != VK_SUCCESS) return false;
        return vk.waitForFences(view.device, 1, &fence, VK_TRUE, 10000000000ULL) == VK_SUCCESS;
    }

    bool uploadToDevice(S3dBuf& dst, const void* srcData, VkDeviceSize bytes, VkFlags extraUsage = 0u) {
        if (!ensureHostBuf(bufStaging, bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) return false;
        if (!ensureDeviceBuf(dst, bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | extraUsage)) {
            return false;
        }
        std::memcpy(bufStaging.mapped, srcData, static_cast<size_t>(bytes));
        if (!beginCmd()) return false;
        VkBufferCopy region{0, 0, bytes};
        vk.cmdCopyBuffer(cmdBuf, bufStaging.buffer, dst.buffer, 1, &region);
        return submitAndWait();
    }

    void bindSet(
        uint32_t setIdx,
        VkBuffer b0, VkDeviceSize s0,
        VkBuffer b1, VkDeviceSize s1,
        VkBuffer b2, VkDeviceSize s2,
        VkBuffer b3, VkDeviceSize s3,
        VkBuffer b4, VkDeviceSize s4,
        VkBuffer b5, VkDeviceSize s5,
        VkBuffer ubo, VkDeviceSize uboSize) {
        VkDescriptorBufferInfo bi[7]{
            {b0 ? b0 : bufDummy.buffer, 0, b0 ? s0 : 64},
            {b1 ? b1 : bufDummy.buffer, 0, b1 ? s1 : 64},
            {b2 ? b2 : bufDummy.buffer, 0, b2 ? s2 : 64},
            {b3 ? b3 : bufDummy.buffer, 0, b3 ? s3 : 64},
            {b4 ? b4 : bufDummy.buffer, 0, b4 ? s4 : 64},
            {b5 ? b5 : bufDummy.buffer, 0, b5 ? s5 : 64},
            {ubo, 0, uboSize},
        };
        VkWriteDescriptorSet writes[7]{};
        for (uint32_t i = 0; i < 7u; ++i) {
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[i].dstSet = descSets[setIdx];
            writes[i].dstBinding = i;
            writes[i].descriptorCount = 1;
            writes[i].descriptorType = (i == 6u) ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[i].pBufferInfo = &bi[i];
        }
        vk.updateDescriptorSets(view.device, 7u, writes, 0, nullptr);
    }

    void computeBarrier() {
        VkMemoryBarrier mb{};
        mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT;
        vk.cmdPipelineBarrier(
            cmdBuf,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 1, &mb, 0, nullptr, 0, nullptr);
    }

    bool init(FujiVkContext* ctx) {
        if (initialized) return true;
        if (!fuji_vk_internal_get_device_view(ctx, &view)) {
            fuji_vk_internal_set_error(ctx, "Scene3D: failed to obtain Vulkan device view");
            return false;
        }
        auto load = [&](const char* name) -> PFN_vkVoidFunction {
            return view.getDeviceProcAddr(view.device, name);
        };
#define LOAD_S3D(field, sym) \
        vk.field = reinterpret_cast<decltype(vk.field)>(load(#sym)); \
        if (!vk.field) { fuji_vk_internal_set_error(ctx, "Scene3D: missing Vulkan symbol " #sym); return false; }

        LOAD_S3D(createBuffer, vkCreateBuffer);
        LOAD_S3D(destroyBuffer, vkDestroyBuffer);
        LOAD_S3D(getBufferMemoryRequirements, vkGetBufferMemoryRequirements);
        LOAD_S3D(allocateMemory, vkAllocateMemory);
        LOAD_S3D(freeMemory, vkFreeMemory);
        LOAD_S3D(bindBufferMemory, vkBindBufferMemory);
        LOAD_S3D(mapMemory, vkMapMemory);
        LOAD_S3D(unmapMemory, vkUnmapMemory);
        LOAD_S3D(createShaderModule, vkCreateShaderModule);
        LOAD_S3D(destroyShaderModule, vkDestroyShaderModule);
        LOAD_S3D(createDescriptorSetLayout, vkCreateDescriptorSetLayout);
        LOAD_S3D(destroyDescriptorSetLayout, vkDestroyDescriptorSetLayout);
        LOAD_S3D(createPipelineLayout, vkCreatePipelineLayout);
        LOAD_S3D(destroyPipelineLayout, vkDestroyPipelineLayout);
        LOAD_S3D(createComputePipelines, vkCreateComputePipelines);
        LOAD_S3D(destroyPipeline, vkDestroyPipeline);
        LOAD_S3D(createDescriptorPool, vkCreateDescriptorPool);
        LOAD_S3D(destroyDescriptorPool, vkDestroyDescriptorPool);
        LOAD_S3D(allocateDescriptorSets, vkAllocateDescriptorSets);
        LOAD_S3D(updateDescriptorSets, vkUpdateDescriptorSets);
        LOAD_S3D(allocateCommandBuffers, vkAllocateCommandBuffers);
        LOAD_S3D(resetCommandBuffer, vkResetCommandBuffer);
        LOAD_S3D(beginCommandBuffer, vkBeginCommandBuffer);
        LOAD_S3D(endCommandBuffer, vkEndCommandBuffer);
        LOAD_S3D(cmdBindPipeline, vkCmdBindPipeline);
        LOAD_S3D(cmdBindDescriptorSets, vkCmdBindDescriptorSets);
        LOAD_S3D(cmdDispatch, vkCmdDispatch);
        LOAD_S3D(cmdPipelineBarrier, vkCmdPipelineBarrier);
        LOAD_S3D(cmdCopyBuffer, vkCmdCopyBuffer);
        LOAD_S3D(cmdFillBuffer, vkCmdFillBuffer);
        LOAD_S3D(createFence, vkCreateFence);
        LOAD_S3D(destroyFence, vkDestroyFence);
        LOAD_S3D(resetFences, vkResetFences);
        LOAD_S3D(waitForFences, vkWaitForFences);
        LOAD_S3D(queueSubmit, vkQueueSubmit);
        LOAD_S3D(deviceWaitIdle, vkDeviceWaitIdle);
#undef LOAD_S3D

        VkCommandBufferAllocateInfo cbai{};
        cbai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cbai.commandPool = view.cmdPool;
        cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cbai.commandBufferCount = 1;
        if (vk.allocateCommandBuffers(view.device, &cbai, &cmdBuf) != VK_SUCCESS) {
            fuji_vk_internal_set_error(ctx, "Scene3D: vkAllocateCommandBuffers failed");
            return false;
        }
        VkFenceCreateInfo fci{};
        fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        if (vk.createFence(view.device, &fci, nullptr, &fence) != VK_SUCCESS) {
            fuji_vk_internal_set_error(ctx, "Scene3D: vkCreateFence failed");
            return false;
        }

        VkDescriptorSetLayoutBinding bindings[7]{};
        for (uint32_t i = 0; i < 7u; ++i) {
            bindings[i].binding = i;
            bindings[i].descriptorType = (i == 6u) ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo dslci{};
        dslci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        dslci.bindingCount = 7u;
        dslci.pBindings = bindings;
        if (vk.createDescriptorSetLayout(view.device, &dslci, nullptr, &descSetLayout) != VK_SUCCESS) {
            fuji_vk_internal_set_error(ctx, "Scene3D: vkCreateDescriptorSetLayout failed");
            return false;
        }
        VkPipelineLayoutCreateInfo plci{};
        plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        plci.setLayoutCount = 1;
        plci.pSetLayouts = &descSetLayout;
        if (vk.createPipelineLayout(view.device, &plci, nullptr, &pipelineLayout) != VK_SUCCESS) {
            fuji_vk_internal_set_error(ctx, "Scene3D: vkCreatePipelineLayout failed");
            return false;
        }

        VkDescriptorPoolSize poolSizes[2]{
            {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, kNumDescSets * 6u},
            {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, kNumDescSets},
        };
        VkDescriptorPoolCreateInfo dpci{};
        dpci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        dpci.maxSets = kNumDescSets;
        dpci.poolSizeCount = 2;
        dpci.pPoolSizes = poolSizes;
        if (vk.createDescriptorPool(view.device, &dpci, nullptr, &descPool) != VK_SUCCESS) {
            fuji_vk_internal_set_error(ctx, "Scene3D: vkCreateDescriptorPool failed");
            return false;
        }
        VkDescriptorSetLayout layouts[kNumDescSets];
        for (uint32_t i = 0; i < kNumDescSets; ++i) layouts[i] = descSetLayout;
        VkDescriptorSetAllocateInfo dsai{};
        dsai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        dsai.descriptorPool = descPool;
        dsai.descriptorSetCount = kNumDescSets;
        dsai.pSetLayouts = layouts;
        if (vk.allocateDescriptorSets(view.device, &dsai, descSets) != VK_SUCCESS) {
            fuji_vk_internal_set_error(ctx, "Scene3D: vkAllocateDescriptorSets failed");
            return false;
        }

        for (uint32_t s = 0; s < FUJI_VK_SCENE3D_SHADER_COUNT; ++s) {
            const uint32_t* words = nullptr;
            uint32_t wordCount = 0u;
            if (fuji_vk_scene3d_get_spirv_words(s, &words, &wordCount) != FUJI_VK_OK) {
                fuji_vk_internal_set_error(ctx, "Scene3D: missing SPIR-V shader");
                return false;
            }
            VkShaderModuleCreateInfo smci{};
            smci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
            smci.codeSize = static_cast<size_t>(wordCount) * 4u;
            smci.pCode = words;
            if (vk.createShaderModule(view.device, &smci, nullptr, &shaderModules[s]) != VK_SUCCESS) {
                fuji_vk_internal_set_error(ctx, "Scene3D: vkCreateShaderModule failed");
                return false;
            }
            VkComputePipelineCreateInfo cpci{};
            cpci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
            cpci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            cpci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            cpci.stage.module = shaderModules[s];
            cpci.stage.pName = "main";
            cpci.layout = pipelineLayout;
            if (vk.createComputePipelines(view.device, nullptr, 1, &cpci, nullptr, &pipelines[s]) != VK_SUCCESS) {
                fuji_vk_internal_set_error(ctx, ("Scene3D: vkCreateComputePipelines failed for shader " + std::to_string(s)).c_str());
                return false;
            }
        }

        if (!ensureDeviceBuf(bufDummy, 256, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT) ||
            !ensureHostBuf(bufUbo, 4096, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)) {
            fuji_vk_internal_set_error(ctx, "Scene3D: failed to allocate dummy/UBO buffers");
            return false;
        }

        initialized = true;
        return true;
    }

    void cleanup() {
        if (view.device && vk.deviceWaitIdle) vk.deviceWaitIdle(view.device);
        freeBuf(bufDummy);
        freeBuf(bufUbo);
        freeBuf(bufStaging);
        freeBuf(bufDepthGrid);
        freeBuf(bufReadback);
        freeBuf(bufMeshVerts);
        freeBuf(bufMeshIndices);
        freeBuf(bufStaticStage);
        freeBuf(bufXformVerts);
        freeBuf(bufScreenTris);
        freeBuf(bufTileCounts);
        freeBuf(bufTileTris);
        freeBuf(bufQrMatrix);
        freeBuf(bufMonaFg);
        freeBuf(bufMonaBg);
        freeBuf(bufLayeredBase);
        if (view.device) {
            for (uint32_t s = 0; s < FUJI_VK_SCENE3D_SHADER_COUNT; ++s) {
                if (pipelines[s] && vk.destroyPipeline) vk.destroyPipeline(view.device, pipelines[s], nullptr);
                if (shaderModules[s] && vk.destroyShaderModule) vk.destroyShaderModule(view.device, shaderModules[s], nullptr);
                pipelines[s] = nullptr;
                shaderModules[s] = nullptr;
            }
            if (descPool && vk.destroyDescriptorPool) vk.destroyDescriptorPool(view.device, descPool, nullptr);
            if (pipelineLayout && vk.destroyPipelineLayout) vk.destroyPipelineLayout(view.device, pipelineLayout, nullptr);
            if (descSetLayout && vk.destroyDescriptorSetLayout) vk.destroyDescriptorSetLayout(view.device, descSetLayout, nullptr);
            if (fence && vk.destroyFence) vk.destroyFence(view.device, fence, nullptr);
            descPool = nullptr;
            pipelineLayout = nullptr;
            descSetLayout = nullptr;
            fence = nullptr;
        }
        initialized = false;
    }

    ~Scene3dState() { cleanup(); }
};

std::mutex g_s3dMutex;
std::unordered_map<FujiVkContext*, std::unique_ptr<Scene3dState>> g_s3dStates;

Scene3dState* get_or_init_state(FujiVkContext* ctx) {
    if (!ctx) return nullptr;
    std::lock_guard<std::mutex> lock(g_s3dMutex);
    auto it = g_s3dStates.find(ctx);
    if (it != g_s3dStates.end() && it->second->initialized) {
        return it->second.get();
    }
    auto st = std::make_unique<Scene3dState>();
    if (!st->init(ctx)) return nullptr;
    Scene3dState* raw = st.get();
    g_s3dStates[ctx] = std::move(st);
    return raw;
}

} /* namespace */

/* Called from fuji_vk_destroy in fuji_vulkan_optics.cpp BEFORE destroying VkDevice. */
extern "C" void fuji_vk_scene3d_destroy_for_context(FujiVkContext* ctx) {
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(g_s3dMutex);
    g_s3dStates.erase(ctx);
}

extern "C" {

FUJI_VK_EXPORT int32_t fuji_vk_scene3d_get_spirv_words(
    uint32_t shader_id,
    const uint32_t** out_words,
    uint32_t* out_word_count) {
    if (!out_words || !out_word_count) return FUJI_VK_ERR_INVALID_ARG;
    switch (shader_id) {
        case FUJI_VK_SCENE3D_SHADER_MF_VERTEX:
            *out_words = kSpvScene3dMilkFrogVertex;
            *out_word_count = static_cast<uint32_t>(sizeof(kSpvScene3dMilkFrogVertex) / 4u);
            return FUJI_VK_OK;
        case FUJI_VK_SCENE3D_SHADER_MF_BIN:
            *out_words = kSpvScene3dMilkFrogBin;
            *out_word_count = static_cast<uint32_t>(sizeof(kSpvScene3dMilkFrogBin) / 4u);
            return FUJI_VK_OK;
        case FUJI_VK_SCENE3D_SHADER_MF_RASTER:
            *out_words = kSpvScene3dMilkFrogRaster;
            *out_word_count = static_cast<uint32_t>(sizeof(kSpvScene3dMilkFrogRaster) / 4u);
            return FUJI_VK_OK;
        case FUJI_VK_SCENE3D_SHADER_QR_BALL:
            *out_words = kSpvScene3dQrBall;
            *out_word_count = static_cast<uint32_t>(sizeof(kSpvScene3dQrBall) / 4u);
            return FUJI_VK_OK;
        case FUJI_VK_SCENE3D_SHADER_MONA_LISA:
            *out_words = kSpvScene3dMonaLisa;
            *out_word_count = static_cast<uint32_t>(sizeof(kSpvScene3dMonaLisa) / 4u);
            return FUJI_VK_OK;
        case FUJI_VK_SCENE3D_SHADER_LAYERED:
            *out_words = kSpvScene3dLayered;
            *out_word_count = static_cast<uint32_t>(sizeof(kSpvScene3dLayered) / 4u);
            return FUJI_VK_OK;
        default:
            return FUJI_VK_ERR_INVALID_ARG;
    }
}

FUJI_VK_EXPORT int32_t fuji_vk_scene3d_upload_mesh(
    FujiVkContext* ctx,
    uint64_t mesh_key,
    const float* vertex_data,
    uint32_t vertex_count,
    const uint32_t* indices,
    uint32_t tri_count,
    double* out_gpu_ms) {
    if (!ctx || mesh_key == 0ULL || !vertex_data || vertex_count == 0u || !indices || tri_count == 0u) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    Scene3dState* st = get_or_init_state(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;
    if (st->cachedMeshKey == mesh_key && st->cachedMeshVerts == vertex_count && st->cachedMeshTris == tri_count &&
        st->bufMeshVerts.buffer && st->bufMeshIndices.buffer) {
        if (out_gpu_ms) *out_gpu_ms = 0.0;
        return FUJI_VK_OK;
    }
    auto t0 = std::chrono::steady_clock::now();
    const VkDeviceSize vertBytes = static_cast<VkDeviceSize>(vertex_count) * 48ULL;
    const VkDeviceSize idxBytes = static_cast<VkDeviceSize>(tri_count) * 12ULL;
    if (!st->uploadToDevice(st->bufMeshVerts, vertex_data, vertBytes) ||
        !st->uploadToDevice(st->bufMeshIndices, indices, idxBytes)) {
        st->cachedMeshKey = 0ULL;
        fuji_vk_internal_set_error(ctx, "Scene3D: failed to upload static 3D mesh to VRAM");
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    st->cachedMeshKey = mesh_key;
    st->cachedMeshVerts = vertex_count;
    st->cachedMeshTris = tri_count;
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_scene3d_upload_stage(
    FujiVkContext* ctx,
    uint64_t stage_key,
    const float* stage_rgba_depth,
    uint32_t width,
    uint32_t height,
    double* out_gpu_ms) {
    if (!ctx || stage_key == 0ULL || !stage_rgba_depth || width == 0u || height == 0u) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    Scene3dState* st = get_or_init_state(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;
    if (st->cachedStageKey == stage_key && st->cachedStageW == width && st->cachedStageH == height && st->bufStaticStage.buffer) {
        if (out_gpu_ms) *out_gpu_ms = 0.0;
        return FUJI_VK_OK;
    }
    auto t0 = std::chrono::steady_clock::now();
    const VkDeviceSize bytes = static_cast<VkDeviceSize>(width) * height * 16ULL;
    if (!st->uploadToDevice(st->bufStaticStage, stage_rgba_depth, bytes)) {
        st->cachedStageKey = 0ULL;
        fuji_vk_internal_set_error(ctx, "Scene3D: failed to upload static studio stage to VRAM");
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    st->cachedStageKey = stage_key;
    st->cachedStageW = width;
    st->cachedStageH = height;
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_scene3d_render_milk_frog(
    FujiVkContext* ctx,
    uint64_t out_scene_key,
    const FujiVkScene3dMilkFrogParams* params,
    float* out_depth_grid,
    uint32_t out_depth_grid_len,
    double* out_gpu_ms) {
    if (!ctx || out_scene_key == 0ULL || !params || params->width == 0u || params->height == 0u) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    Scene3dState* st = get_or_init_state(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;
    if (!st->bufMeshVerts.buffer || !st->bufMeshIndices.buffer || !st->bufStaticStage.buffer ||
        params->vertexCount > st->cachedMeshVerts || params->triCount > st->cachedMeshTris ||
        params->width != st->cachedStageW || params->height != st->cachedStageH) {
        fuji_vk_internal_set_error(ctx, "Scene3D: Milk Frog mesh/stage not uploaded or dimension mismatch");
        return FUJI_VK_ERR_INVALID_ARG;
    }

    auto t0 = std::chrono::steady_clock::now();
    const uint32_t w = params->width;
    const uint32_t h = params->height;
    const uint32_t tilesX = (w + FUJI_VK_SCENE3D_TILE_SIZE - 1u) / FUJI_VK_SCENE3D_TILE_SIZE;
    const uint32_t tilesY = (h + FUJI_VK_SCENE3D_TILE_SIZE - 1u) / FUJI_VK_SCENE3D_TILE_SIZE;
    const uint32_t numTiles = tilesX * tilesY;

    FujiVkScene3dMilkFrogParams ubo = *params;
    ubo.tilesX = tilesX;
    ubo.tilesY = tilesY;
    if (ubo.orbCount > FUJI_VK_SCENE3D_MAX_ORBS) ubo.orbCount = FUJI_VK_SCENE3D_MAX_ORBS;

    const VkDeviceSize sceneBytes = static_cast<VkDeviceSize>(w) * h * 16ULL;
    const VkDeviceSize xformBytes = static_cast<VkDeviceSize>(ubo.vertexCount) * 32ULL;
    const VkDeviceSize striBytes = static_cast<VkDeviceSize>(ubo.triCount) * 96ULL;
    const VkDeviceSize tcntBytes = static_cast<VkDeviceSize>(numTiles) * 4ULL;
    // One membership bit per source triangle, followed by short occupied-word lists per tile.
    // Common tiles avoid scanning empty words; dense tiles retain every triangle.
    constexpr uint32_t sparseTileWords = 64;
    const VkDeviceSize triWords = (static_cast<VkDeviceSize>(ubo.triCount) + 31) / 32;
    const VkDeviceSize maskBytes = static_cast<VkDeviceSize>(numTiles) * triWords * sizeof(uint32_t);
    const VkDeviceSize ttrisBytes = maskBytes + static_cast<VkDeviceSize>(numTiles) * sparseTileWords * sizeof(uint32_t);
    if (ttrisBytes / sizeof(uint32_t) > UINT32_MAX) return FUJI_VK_ERR_INVALID_ARG;
    const VkDeviceSize gridBytes = static_cast<VkDeviceSize>(numTiles) * 4ULL;

    VkBuffer dstSceneBuf = nullptr;
    if (!fuji_vk_internal_acquire_scene_device_buffer(ctx, sceneBytes, &dstSceneBuf) ||
        !st->ensureDeviceBuf(st->bufXformVerts, xformBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) ||
        !st->ensureDeviceBuf(st->bufScreenTris, striBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) ||
        !st->ensureDeviceBuf(st->bufTileCounts, tcntBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT) ||
        !st->ensureDeviceBuf(st->bufTileTris, ttrisBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT) ||
        !st->ensureHostBuf(st->bufDepthGrid, gridBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) {
        fuji_vk_internal_set_error(ctx, "Scene3D: failed to allocate Milk Frog GPU working buffers");
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    std::memcpy(st->bufUbo.mapped, &ubo, sizeof(ubo));

    const VkDeviceSize vertBytes = static_cast<VkDeviceSize>(st->cachedMeshVerts) * 48ULL;
    const VkDeviceSize idxBytes = static_cast<VkDeviceSize>(st->cachedMeshTris) * 12ULL;

    /* Pass 0: vertex deform + lighting + projection */
    st->bindSet(0u,
        st->bufMeshVerts.buffer, vertBytes,
        st->bufXformVerts.buffer, xformBytes,
        nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0,
        st->bufUbo.buffer, sizeof(ubo));
    /* Pass 1: double-sided screen tri pack + 16x16 tile binning */
    st->bindSet(1u,
        st->bufMeshIndices.buffer, idxBytes,
        st->bufXformVerts.buffer, xformBytes,
        st->bufScreenTris.buffer, striBytes,
        st->bufTileCounts.buffer, tcntBytes,
        st->bufTileTris.buffer, ttrisBytes,
        nullptr, 0,
        st->bufUbo.buffer, sizeof(ubo));
    /* Pass 2: tiled Z-buffer rasterizer + backdrop + pedestal/ruler + HDR orbs */
    st->bindSet(2u,
        st->bufStaticStage.buffer, sceneBytes,
        st->bufScreenTris.buffer, striBytes,
        st->bufTileCounts.buffer, tcntBytes,
        st->bufTileTris.buffer, ttrisBytes,
        dstSceneBuf, sceneBytes,
        st->bufDepthGrid.buffer, gridBytes,
        st->bufUbo.buffer, sizeof(ubo));

    if (!st->beginCmd()) {
        fuji_vk_internal_set_error(ctx, "Scene3D: vkBeginCommandBuffer failed for Milk Frog");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    /* Clear per-tile triangle counters to 0 */
    st->vk.cmdFillBuffer(st->cmdBuf, st->bufTileCounts.buffer, 0, tcntBytes, 0u);
    if (maskBytes) st->vk.cmdFillBuffer(st->cmdBuf, st->bufTileTris.buffer, 0, maskBytes, 0u);

    /* Pass 0: Vertex transform */
    st->vk.cmdBindPipeline(st->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->pipelines[FUJI_VK_SCENE3D_SHADER_MF_VERTEX]);
    st->vk.cmdBindDescriptorSets(st->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->pipelineLayout, 0, 1, &st->descSets[0], 0, nullptr);
    st->vk.cmdDispatch(st->cmdBuf, (ubo.vertexCount + 63u) / 64u, 1u, 1u);
    st->computeBarrier();

    /* Pass 1: Triangle tile binning */
    st->vk.cmdBindPipeline(st->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->pipelines[FUJI_VK_SCENE3D_SHADER_MF_BIN]);
    st->vk.cmdBindDescriptorSets(st->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->pipelineLayout, 0, 1, &st->descSets[1], 0, nullptr);
    st->vk.cmdDispatch(st->cmdBuf, (ubo.triCount + 63u) / 64u, 1u, 1u);
    st->computeBarrier();

    /* Pass 2: Tiled rasterization straight into DEVICE_LOCAL bufSceneDevice */
    st->vk.cmdBindPipeline(st->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->pipelines[FUJI_VK_SCENE3D_SHADER_MF_RASTER]);
    st->vk.cmdBindDescriptorSets(st->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->pipelineLayout, 0, 1, &st->descSets[2], 0, nullptr);
    st->vk.cmdDispatch(st->cmdBuf, (w + 7u) / 8u, (h + 7u) / 8u, 1u);

    if (!st->submitAndWait()) {
        fuji_vk_internal_set_error(ctx, "Scene3D: Milk Frog GPU dispatch failed");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    fuji_vk_internal_commit_scene_device_buffer(ctx, out_scene_key, sceneBytes);
    if (out_depth_grid && out_depth_grid_len >= numTiles) {
        std::memcpy(out_depth_grid, st->bufDepthGrid.mapped, static_cast<size_t>(gridBytes));
    }
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_scene3d_upload_qr_matrix(
    FujiVkContext* ctx,
    uint64_t qr_key,
    const uint32_t* row_bits_32,
    double* out_gpu_ms) {
    if (!ctx || qr_key == 0ULL || !row_bits_32) return FUJI_VK_ERR_INVALID_ARG;
    Scene3dState* st = get_or_init_state(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;
    if (st->cachedQrKey == qr_key && st->bufQrMatrix.buffer) {
        if (out_gpu_ms) *out_gpu_ms = 0.0;
        return FUJI_VK_OK;
    }
    auto t0 = std::chrono::steady_clock::now();
    if (!st->uploadToDevice(st->bufQrMatrix, row_bits_32, 32ULL * 4ULL)) {
        st->cachedQrKey = 0ULL;
        fuji_vk_internal_set_error(ctx, "Scene3D: failed to upload QR matrix to VRAM");
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    st->cachedQrKey = qr_key;
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_scene3d_render_qr_ball(
    FujiVkContext* ctx,
    uint64_t out_scene_key,
    const FujiVkScene3dQrBallParams* params,
    float* out_depth_grid,
    uint32_t out_depth_grid_len,
    double* out_gpu_ms) {
    if (!ctx || out_scene_key == 0ULL || !params || params->width == 0u || params->height == 0u) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    Scene3dState* st = get_or_init_state(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;
    if (!st->bufQrMatrix.buffer) {
        fuji_vk_internal_set_error(ctx, "Scene3D: QR matrix not uploaded");
        return FUJI_VK_ERR_INVALID_ARG;
    }

    auto t0 = std::chrono::steady_clock::now();
    const uint32_t w = params->width;
    const uint32_t h = params->height;
    const uint32_t tilesX = (w + FUJI_VK_SCENE3D_TILE_SIZE - 1u) / FUJI_VK_SCENE3D_TILE_SIZE;
    const uint32_t tilesY = (h + FUJI_VK_SCENE3D_TILE_SIZE - 1u) / FUJI_VK_SCENE3D_TILE_SIZE;
    const uint32_t numTiles = tilesX * tilesY;

    FujiVkScene3dQrBallParams ubo = *params;
    ubo.tilesX = tilesX;
    ubo.tilesY = tilesY;

    const VkDeviceSize sceneBytes = static_cast<VkDeviceSize>(w) * h * 16ULL;
    const VkDeviceSize gridBytes = static_cast<VkDeviceSize>(numTiles) * 4ULL;
    VkBuffer dstSceneBuf = nullptr;
    if (!fuji_vk_internal_acquire_scene_device_buffer(ctx, sceneBytes, &dstSceneBuf) ||
        !st->ensureHostBuf(st->bufDepthGrid, gridBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) {
        fuji_vk_internal_set_error(ctx, "Scene3D: failed to allocate QR-Ball GPU buffers");
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    std::memcpy(st->bufUbo.mapped, &ubo, sizeof(ubo));
    st->bindSet(3u,
        st->bufQrMatrix.buffer, 32ULL * 4ULL,
        nullptr, 0, nullptr, 0, nullptr, 0,
        dstSceneBuf, sceneBytes,
        st->bufDepthGrid.buffer, gridBytes,
        st->bufUbo.buffer, sizeof(ubo));

    if (!st->beginCmd()) return FUJI_VK_ERR_DISPATCH_FAILED;
    st->vk.cmdBindPipeline(st->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->pipelines[FUJI_VK_SCENE3D_SHADER_QR_BALL]);
    st->vk.cmdBindDescriptorSets(st->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->pipelineLayout, 0, 1, &st->descSets[3], 0, nullptr);
    st->vk.cmdDispatch(st->cmdBuf, (w + 7u) / 8u, (h + 7u) / 8u, 1u);
    if (!st->submitAndWait()) {
        fuji_vk_internal_set_error(ctx, "Scene3D: QR-Ball GPU dispatch failed");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    fuji_vk_internal_commit_scene_device_buffer(ctx, out_scene_key, sceneBytes);
    if (out_depth_grid && out_depth_grid_len >= numTiles) {
        std::memcpy(out_depth_grid, st->bufDepthGrid.mapped, static_cast<size_t>(gridBytes));
    }
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_scene3d_upload_mona_lisa(
    FujiVkContext* ctx,
    uint64_t portrait_key,
    const float* fg_rgba,
    const float* bg_rgb_depth_offset,
    uint32_t src_width,
    uint32_t src_height,
    double* out_gpu_ms) {
    if (!ctx || portrait_key == 0ULL || !fg_rgba || !bg_rgb_depth_offset || src_width == 0u || src_height == 0u) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    Scene3dState* st = get_or_init_state(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;
    if (st->cachedMonaKey == portrait_key && st->cachedMonaW == src_width && st->cachedMonaH == src_height &&
        st->bufMonaFg.buffer && st->bufMonaBg.buffer) {
        if (out_gpu_ms) *out_gpu_ms = 0.0;
        return FUJI_VK_OK;
    }
    auto t0 = std::chrono::steady_clock::now();
    const VkDeviceSize bytes = static_cast<VkDeviceSize>(src_width) * src_height * 16ULL;
    if (!st->uploadToDevice(st->bufMonaFg, fg_rgba, bytes) ||
        !st->uploadToDevice(st->bufMonaBg, bg_rgb_depth_offset, bytes)) {
        st->cachedMonaKey = 0ULL;
        fuji_vk_internal_set_error(ctx, "Scene3D: failed to upload Mona Lisa portrait to VRAM");
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    st->cachedMonaKey = portrait_key;
    st->cachedMonaW = src_width;
    st->cachedMonaH = src_height;
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_scene3d_render_mona_lisa(
    FujiVkContext* ctx,
    uint64_t out_scene_key,
    const FujiVkScene3dMonaLisaParams* params,
    float* out_depth_grid,
    uint32_t out_depth_grid_len,
    double* out_gpu_ms) {
    if (!ctx || out_scene_key == 0ULL || !params || params->dstWidth == 0u || params->dstHeight == 0u) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    Scene3dState* st = get_or_init_state(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;
    if (!st->bufMonaFg.buffer || !st->bufMonaBg.buffer ||
        params->srcWidth != st->cachedMonaW || params->srcHeight != st->cachedMonaH) {
        fuji_vk_internal_set_error(ctx, "Scene3D: Mona Lisa portrait not uploaded or size mismatch");
        return FUJI_VK_ERR_INVALID_ARG;
    }

    auto t0 = std::chrono::steady_clock::now();
    const uint32_t w = params->dstWidth;
    const uint32_t h = params->dstHeight;
    const uint32_t tilesX = (w + FUJI_VK_SCENE3D_TILE_SIZE - 1u) / FUJI_VK_SCENE3D_TILE_SIZE;
    const uint32_t tilesY = (h + FUJI_VK_SCENE3D_TILE_SIZE - 1u) / FUJI_VK_SCENE3D_TILE_SIZE;
    const uint32_t numTiles = tilesX * tilesY;

    FujiVkScene3dMonaLisaParams ubo = *params;
    ubo.tilesX = tilesX;
    ubo.tilesY = tilesY;

    const VkDeviceSize srcBytes = static_cast<VkDeviceSize>(st->cachedMonaW) * st->cachedMonaH * 16ULL;
    const VkDeviceSize sceneBytes = static_cast<VkDeviceSize>(w) * h * 16ULL;
    const VkDeviceSize gridBytes = static_cast<VkDeviceSize>(numTiles) * 4ULL;
    VkBuffer dstSceneBuf = nullptr;
    if (!fuji_vk_internal_acquire_scene_device_buffer(ctx, sceneBytes, &dstSceneBuf) ||
        !st->ensureHostBuf(st->bufDepthGrid, gridBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) {
        fuji_vk_internal_set_error(ctx, "Scene3D: failed to allocate Mona Lisa GPU buffers");
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    std::memcpy(st->bufUbo.mapped, &ubo, sizeof(ubo));
    st->bindSet(3u,
        st->bufMonaFg.buffer, srcBytes,
        st->bufMonaBg.buffer, srcBytes,
        nullptr, 0, nullptr, 0,
        dstSceneBuf, sceneBytes,
        st->bufDepthGrid.buffer, gridBytes,
        st->bufUbo.buffer, sizeof(ubo));

    if (!st->beginCmd()) return FUJI_VK_ERR_DISPATCH_FAILED;
    st->vk.cmdBindPipeline(st->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->pipelines[FUJI_VK_SCENE3D_SHADER_MONA_LISA]);
    st->vk.cmdBindDescriptorSets(st->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->pipelineLayout, 0, 1, &st->descSets[3], 0, nullptr);
    st->vk.cmdDispatch(st->cmdBuf, (w + 7u) / 8u, (h + 7u) / 8u, 1u);
    if (!st->submitAndWait()) {
        fuji_vk_internal_set_error(ctx, "Scene3D: Mona Lisa GPU dispatch failed");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    fuji_vk_internal_commit_scene_device_buffer(ctx, out_scene_key, sceneBytes);
    if (out_depth_grid && out_depth_grid_len >= numTiles) {
        std::memcpy(out_depth_grid, st->bufDepthGrid.mapped, static_cast<size_t>(gridBytes));
    }
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_scene3d_upload_layered_base(
    FujiVkContext* ctx,
    uint64_t base_key,
    const float* base_rgba_depth,
    uint32_t width,
    uint32_t height,
    double* out_gpu_ms) {
    if (!ctx || base_key == 0ULL || !base_rgba_depth || width == 0u || height == 0u) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    Scene3dState* st = get_or_init_state(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;
    if (st->cachedLayeredKey == base_key && st->cachedLayeredW == width && st->cachedLayeredH == height && st->bufLayeredBase.buffer) {
        if (out_gpu_ms) *out_gpu_ms = 0.0;
        return FUJI_VK_OK;
    }
    auto t0 = std::chrono::steady_clock::now();
    const VkDeviceSize bytes = static_cast<VkDeviceSize>(width) * height * 16ULL;
    if (!st->uploadToDevice(st->bufLayeredBase, base_rgba_depth, bytes)) {
        st->cachedLayeredKey = 0ULL;
        fuji_vk_internal_set_error(ctx, "Scene3D: failed to upload layered studio base to VRAM");
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    st->cachedLayeredKey = base_key;
    st->cachedLayeredW = width;
    st->cachedLayeredH = height;
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_scene3d_render_layered(
    FujiVkContext* ctx,
    uint64_t out_scene_key,
    const FujiVkScene3dLayeredParams* params,
    float* out_depth_grid,
    uint32_t out_depth_grid_len,
    double* out_gpu_ms) {
    if (!ctx || out_scene_key == 0ULL || !params || params->width == 0u || params->height == 0u) {
        return FUJI_VK_ERR_INVALID_ARG;
    }
    Scene3dState* st = get_or_init_state(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;
    if (!st->bufLayeredBase.buffer || params->width != st->cachedLayeredW || params->height != st->cachedLayeredH) {
        fuji_vk_internal_set_error(ctx, "Scene3D: layered studio base not uploaded or size mismatch");
        return FUJI_VK_ERR_INVALID_ARG;
    }

    auto t0 = std::chrono::steady_clock::now();
    const uint32_t w = params->width;
    const uint32_t h = params->height;
    const uint32_t tilesX = (w + FUJI_VK_SCENE3D_TILE_SIZE - 1u) / FUJI_VK_SCENE3D_TILE_SIZE;
    const uint32_t tilesY = (h + FUJI_VK_SCENE3D_TILE_SIZE - 1u) / FUJI_VK_SCENE3D_TILE_SIZE;
    const uint32_t numTiles = tilesX * tilesY;

    FujiVkScene3dLayeredParams ubo = *params;
    ubo.tilesX = tilesX;
    ubo.tilesY = tilesY;

    const VkDeviceSize sceneBytes = static_cast<VkDeviceSize>(w) * h * 16ULL;
    const VkDeviceSize gridBytes = static_cast<VkDeviceSize>(numTiles) * 4ULL;
    VkBuffer dstSceneBuf = nullptr;
    if (!fuji_vk_internal_acquire_scene_device_buffer(ctx, sceneBytes, &dstSceneBuf) ||
        !st->ensureHostBuf(st->bufDepthGrid, gridBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)) {
        fuji_vk_internal_set_error(ctx, "Scene3D: failed to allocate layered GPU buffers");
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }

    std::memcpy(st->bufUbo.mapped, &ubo, sizeof(ubo));
    st->bindSet(3u,
        st->bufLayeredBase.buffer, sceneBytes,
        nullptr, 0, nullptr, 0, nullptr, 0,
        dstSceneBuf, sceneBytes,
        st->bufDepthGrid.buffer, gridBytes,
        st->bufUbo.buffer, sizeof(ubo));

    if (!st->beginCmd()) return FUJI_VK_ERR_DISPATCH_FAILED;
    st->vk.cmdBindPipeline(st->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->pipelines[FUJI_VK_SCENE3D_SHADER_LAYERED]);
    st->vk.cmdBindDescriptorSets(st->cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, st->pipelineLayout, 0, 1, &st->descSets[3], 0, nullptr);
    st->vk.cmdDispatch(st->cmdBuf, (w + 7u) / 8u, (h + 7u) / 8u, 1u);
    if (!st->submitAndWait()) {
        fuji_vk_internal_set_error(ctx, "Scene3D: layered reprojection GPU dispatch failed");
        return FUJI_VK_ERR_DISPATCH_FAILED;
    }

    fuji_vk_internal_commit_scene_device_buffer(ctx, out_scene_key, sceneBytes);
    if (out_depth_grid && out_depth_grid_len >= numTiles) {
        std::memcpy(out_depth_grid, st->bufDepthGrid.mapped, static_cast<size_t>(gridBytes));
    }
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    return FUJI_VK_OK;
}

FUJI_VK_EXPORT int32_t fuji_vk_scene3d_readback_scene(
    FujiVkContext* ctx,
    float* out_rgba_depth,
    size_t pixel_count,
    double* out_gpu_ms) {
    if (!ctx || !out_rgba_depth || pixel_count == 0u) return FUJI_VK_ERR_INVALID_ARG;
    Scene3dState* st = get_or_init_state(ctx);
    if (!st) return FUJI_VK_ERR_INIT_FAILED;
    VkBuffer srcBuf = nullptr;
    VkDeviceSize availBytes = 0ULL;
    const VkDeviceSize needBytes = static_cast<VkDeviceSize>(pixel_count) * 16ULL;
    if (!fuji_vk_internal_get_scene_device_buffer(ctx, &srcBuf, &availBytes) || !srcBuf || availBytes < needBytes) {
        fuji_vk_internal_set_error(ctx, "Scene3D: no DEVICE_LOCAL scene buffer available for readback");
        return FUJI_VK_ERR_INVALID_ARG;
    }
    auto t0 = std::chrono::steady_clock::now();
    if (!st->ensureHostBuf(st->bufReadback, needBytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
        return FUJI_VK_ERR_OUT_OF_MEMORY;
    }
    if (!st->beginCmd()) return FUJI_VK_ERR_DISPATCH_FAILED;
    VkBufferCopy region{0, 0, needBytes};
    st->vk.cmdCopyBuffer(st->cmdBuf, srcBuf, st->bufReadback.buffer, 1, &region);
    if (!st->submitAndWait()) return FUJI_VK_ERR_DISPATCH_FAILED;
    std::memcpy(out_rgba_depth, st->bufReadback.mapped, static_cast<size_t>(needBytes));
    auto t1 = std::chrono::steady_clock::now();
    if (out_gpu_ms) *out_gpu_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    return FUJI_VK_OK;
}

} /* extern "C" */
