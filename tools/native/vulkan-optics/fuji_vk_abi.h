#ifndef FUJI_VK_ABI_H
#define FUJI_VK_ABI_H

/*
 * Minimal, self-contained Khronos Vulkan 1.1 C ABI header for zero-dependency
 * dynamic loading (`dlopen("libvulkan.so.1")` / `LoadLibraryW(L"vulkan-1.dll")`).
 *
 * Covers core Vulkan 1.1 compute dispatch + VK_KHR_external_memory / semaphore
 * descriptors for Phase 2 zero-copy game RGB+Depth ingestion.
 */

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define VKAPI_ATTR
#define VKAPI_CALL __stdcall
#define VKAPI_PTR  VKAPI_CALL
#else
#define VKAPI_ATTR
#define VKAPI_CALL
#define VKAPI_PTR
#endif

typedef uint32_t VkFlags;
typedef uint32_t VkBool32;
typedef uint64_t VkDeviceSize;
typedef int32_t  VkResult;

#define VK_TRUE  1u
#define VK_FALSE 0u
#define VK_WHOLE_SIZE (~0ULL)
#define VK_MAKE_API_VERSION(variant, major, minor, patch) \
    ((((uint32_t)(variant)) << 29) | (((uint32_t)(major)) << 22) | (((uint32_t)(minor)) << 12) | ((uint32_t)(patch)))
#define VK_API_VERSION_1_0 VK_MAKE_API_VERSION(0, 1, 0, 0)
#define VK_API_VERSION_1_1 VK_MAKE_API_VERSION(0, 1, 1, 0)

#define VK_DEFINE_HANDLE(object) typedef struct object##_T* object;
#if defined(__LP64__) || defined(_WIN64) || (defined(__x86_64__) && !defined(__ILP32__)) || defined(__aarch64__)
#define VK_DEFINE_NON_DISPATCHABLE_HANDLE(object) typedef struct object##_T* object;
#else
#define VK_DEFINE_NON_DISPATCHABLE_HANDLE(object) typedef uint64_t object;
#endif

VK_DEFINE_HANDLE(VkInstance)
VK_DEFINE_HANDLE(VkPhysicalDevice)
VK_DEFINE_HANDLE(VkDevice)
VK_DEFINE_HANDLE(VkQueue)
VK_DEFINE_HANDLE(VkCommandBuffer)

VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkDeviceMemory)
VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkBuffer)
VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkFence)
VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkSemaphore)
VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkShaderModule)
VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkPipelineCache)
VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkPipelineLayout)
VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkPipeline)
VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkDescriptorSetLayout)
VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkDescriptorPool)
VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkDescriptorSet)
VK_DEFINE_NON_DISPATCHABLE_HANDLE(VkCommandPool)

typedef void (VKAPI_PTR *PFN_vkVoidFunction)(void);

enum {
    VK_SUCCESS = 0,
    VK_TIMEOUT = 2,
    VK_INCOMPLETE = 5,
    VK_ERROR_OUT_OF_HOST_MEMORY = -1,
    VK_ERROR_OUT_OF_DEVICE_MEMORY = -2,
    VK_ERROR_INITIALIZATION_FAILED = -3,
    VK_ERROR_DEVICE_LOST = -4,
    VK_ERROR_EXTENSION_NOT_PRESENT = -7,
    VK_ERROR_INCOMPATIBLE_DRIVER = -9
};

typedef enum VkStructureType {
    VK_STRUCTURE_TYPE_APPLICATION_INFO = 0,
    VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO = 1,
    VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO = 2,
    VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO = 3,
    VK_STRUCTURE_TYPE_SUBMIT_INFO = 4,
    VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO = 5,
    VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE = 6,
    VK_STRUCTURE_TYPE_FENCE_CREATE_INFO = 8,
    VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO = 9,
    VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO = 12,
    VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO = 16,
    VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO = 17,
    VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO = 18,
    VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO = 29,
    VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO = 30,
    VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO = 32,
    VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO = 33,
    VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO = 34,
    VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET = 35,
    VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO = 39,
    VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO = 40,
    VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO = 42,
    VK_STRUCTURE_TYPE_MEMORY_BARRIER = 46,
    VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO = 1000072001,
    VK_STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR = 1000073000,
    VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR = 1000074000,
    VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_WIN32_HANDLE_INFO_KHR = 1000078000,
    VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR = 1000079000
} VkStructureType;

typedef enum VkPhysicalDeviceType {
    VK_PHYSICAL_DEVICE_TYPE_OTHER = 0,
    VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU = 1,
    VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU = 2,
    VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU = 3,
    VK_PHYSICAL_DEVICE_TYPE_CPU = 4
} VkPhysicalDeviceType;

enum {
    VK_QUEUE_GRAPHICS_BIT = 0x00000001,
    VK_QUEUE_COMPUTE_BIT  = 0x00000002,
    VK_QUEUE_TRANSFER_BIT = 0x00000004
};

enum {
    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT  = 0x00000001,
    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT  = 0x00000002,
    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT = 0x00000004,
    VK_MEMORY_PROPERTY_HOST_CACHED_BIT   = 0x00000008
};

enum {
    VK_BUFFER_USAGE_TRANSFER_SRC_BIT   = 0x00000001,
    VK_BUFFER_USAGE_TRANSFER_DST_BIT   = 0x00000002,
    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT = 0x00000010,
    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT = 0x00000020
};

enum {
    VK_SHARING_MODE_EXCLUSIVE = 0
};

enum {
    VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER = 6,
    VK_DESCRIPTOR_TYPE_STORAGE_BUFFER = 7
};

enum {
    VK_SHADER_STAGE_COMPUTE_BIT = 0x00000020
};

enum {
    VK_PIPELINE_BIND_POINT_COMPUTE = 1
};

enum {
    VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT = 0x00000002
};

enum {
    VK_COMMAND_BUFFER_LEVEL_PRIMARY = 0,
    VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT = 0x00000001
};

enum {
    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT = 0x00000800,
    VK_PIPELINE_STAGE_TRANSFER_BIT       = 0x00001000
};

enum {
    VK_ACCESS_SHADER_READ_BIT    = 0x00000020,
    VK_ACCESS_SHADER_WRITE_BIT   = 0x00000040,
    VK_ACCESS_TRANSFER_READ_BIT  = 0x00000800,
    VK_ACCESS_TRANSFER_WRITE_BIT = 0x00001000
};

enum {
    VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT          = 0x00000001,
    VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT       = 0x00000002,
    VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_KMT_BIT   = 0x00000004,
    VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT      = 0x00000008,
    VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT        = 0x00000200
};

#define VK_MAX_PHYSICAL_DEVICE_NAME_SIZE 256
#define VK_UUID_SIZE 16
#define VK_MAX_MEMORY_TYPES 32
#define VK_MAX_MEMORY_HEAPS 16
#define VK_MAX_EXTENSION_NAME_SIZE 256

typedef struct VkAllocationCallbacks VkAllocationCallbacks;

typedef struct VkApplicationInfo {
    VkStructureType sType;
    const void*     pNext;
    const char*     pApplicationName;
    uint32_t        applicationVersion;
    const char*     pEngineName;
    uint32_t        engineVersion;
    uint32_t        apiVersion;
} VkApplicationInfo;

typedef struct VkInstanceCreateInfo {
    VkStructureType          sType;
    const void*              pNext;
    VkFlags                  flags;
    const VkApplicationInfo* pApplicationInfo;
    uint32_t                 enabledLayerCount;
    const char* const*       ppEnabledLayerNames;
    uint32_t                 enabledExtensionCount;
    const char* const*       ppEnabledExtensionNames;
} VkInstanceCreateInfo;

typedef struct VkExtensionProperties {
    char     extensionName[VK_MAX_EXTENSION_NAME_SIZE];
    uint32_t specVersion;
} VkExtensionProperties;

typedef struct VkExtent3D {
    uint32_t width;
    uint32_t height;
    uint32_t depth;
} VkExtent3D;

typedef struct VkQueueFamilyProperties {
    VkFlags    queueFlags;
    uint32_t   queueCount;
    uint32_t   timestampValidBits;
    VkExtent3D minImageTransferGranularity;
} VkQueueFamilyProperties;

typedef struct VkPhysicalDeviceProperties {
    uint32_t             apiVersion;
    uint32_t             driverVersion;
    uint32_t             vendorID;
    uint32_t             deviceID;
    VkPhysicalDeviceType deviceType;
    char                 deviceName[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE];
    uint8_t              pipelineCacheUUID[VK_UUID_SIZE];
    uint8_t              limitsAndSparsePadding[824 - (20 + VK_MAX_PHYSICAL_DEVICE_NAME_SIZE + VK_UUID_SIZE)];
} VkPhysicalDeviceProperties;

typedef struct VkMemoryType {
    VkFlags  propertyFlags;
    uint32_t heapIndex;
} VkMemoryType;

typedef struct VkMemoryHeap {
    VkDeviceSize size;
    VkFlags      flags;
} VkMemoryHeap;

typedef struct VkPhysicalDeviceMemoryProperties {
    uint32_t     memoryTypeCount;
    VkMemoryType memoryTypes[VK_MAX_MEMORY_TYPES];
    uint32_t     memoryHeapCount;
    VkMemoryHeap memoryHeaps[VK_MAX_MEMORY_HEAPS];
} VkPhysicalDeviceMemoryProperties;

typedef struct VkDeviceQueueCreateInfo {
    VkStructureType sType;
    const void*     pNext;
    VkFlags         flags;
    uint32_t        queueFamilyIndex;
    uint32_t        queueCount;
    const float*    pQueuePriorities;
} VkDeviceQueueCreateInfo;

typedef struct VkDeviceCreateInfo {
    VkStructureType                sType;
    const void*                    pNext;
    VkFlags                        flags;
    uint32_t                       queueCreateInfoCount;
    const VkDeviceQueueCreateInfo* pQueueCreateInfos;
    uint32_t                       enabledLayerCount;
    const char* const*             ppEnabledLayerNames;
    uint32_t                       enabledExtensionCount;
    const char* const*             ppEnabledExtensionNames;
    const void*                    pEnabledFeatures;
} VkDeviceCreateInfo;

typedef struct VkBufferCreateInfo {
    VkStructureType sType;
    const void*     pNext;
    VkFlags         flags;
    VkDeviceSize    size;
    VkFlags         usage;
    uint32_t        sharingMode;
    uint32_t        queueFamilyIndexCount;
    const uint32_t* pQueueFamilyIndices;
} VkBufferCreateInfo;

typedef struct VkMemoryRequirements {
    VkDeviceSize size;
    VkDeviceSize alignment;
    uint32_t     memoryTypeBits;
} VkMemoryRequirements;

typedef struct VkMemoryAllocateInfo {
    VkStructureType sType;
    const void*     pNext;
    VkDeviceSize    allocationSize;
    uint32_t        memoryTypeIndex;
} VkMemoryAllocateInfo;

typedef struct VkMappedMemoryRange {
    VkStructureType sType;
    const void*     pNext;
    VkDeviceMemory  memory;
    VkDeviceSize    offset;
    VkDeviceSize    size;
} VkMappedMemoryRange;

typedef struct VkPipelineCacheCreateInfo {
    VkStructureType sType;
    const void*     pNext;
    VkFlags         flags;
    size_t          initialDataSize;
    const void*     pInitialData;
} VkPipelineCacheCreateInfo;

typedef struct VkSpecializationMapEntry {
    uint32_t constantID;
    uint32_t offset;
    size_t   size;
} VkSpecializationMapEntry;

typedef struct VkSpecializationInfo {
    uint32_t                        mapEntryCount;
    const VkSpecializationMapEntry* pMapEntries;
    size_t                          dataSize;
    const void*                     pData;
} VkSpecializationInfo;

typedef struct VkShaderModuleCreateInfo {
    VkStructureType sType;
    const void*     pNext;
    VkFlags         flags;
    size_t          codeSize;
    const uint32_t* pCode;
} VkShaderModuleCreateInfo;

typedef struct VkDescriptorSetLayoutBinding {
    uint32_t    binding;
    uint32_t    descriptorType;
    uint32_t    descriptorCount;
    VkFlags     stageFlags;
    const void* pImmutableSamplers;
} VkDescriptorSetLayoutBinding;

typedef struct VkDescriptorSetLayoutCreateInfo {
    VkStructureType                     sType;
    const void*                         pNext;
    VkFlags                             flags;
    uint32_t                            bindingCount;
    const VkDescriptorSetLayoutBinding* pBindings;
} VkDescriptorSetLayoutCreateInfo;

typedef struct VkPushConstantRange {
    VkFlags  stageFlags;
    uint32_t offset;
    uint32_t size;
} VkPushConstantRange;

typedef struct VkPipelineLayoutCreateInfo {
    VkStructureType              sType;
    const void*                  pNext;
    VkFlags                      flags;
    uint32_t                     setLayoutCount;
    const VkDescriptorSetLayout* pSetLayouts;
    uint32_t                     pushConstantRangeCount;
    const VkPushConstantRange*   pPushConstantRanges;
} VkPipelineLayoutCreateInfo;

typedef struct VkPipelineShaderStageCreateInfo {
    VkStructureType sType;
    const void*     pNext;
    VkFlags         flags;
    VkFlags         stage;
    VkShaderModule  module;
    const char*     pName;
    const void*     pSpecializationInfo;
} VkPipelineShaderStageCreateInfo;

typedef struct VkComputePipelineCreateInfo {
    VkStructureType                 sType;
    const void*                     pNext;
    VkFlags                         flags;
    VkPipelineShaderStageCreateInfo stage;
    VkPipelineLayout                layout;
    VkPipeline                      basePipelineHandle;
    int32_t                         basePipelineIndex;
} VkComputePipelineCreateInfo;

typedef struct VkDescriptorPoolSize {
    uint32_t type;
    uint32_t descriptorCount;
} VkDescriptorPoolSize;

typedef struct VkDescriptorPoolCreateInfo {
    VkStructureType             sType;
    const void*                 pNext;
    VkFlags                     flags;
    uint32_t                    maxSets;
    uint32_t                    poolSizeCount;
    const VkDescriptorPoolSize* pPoolSizes;
} VkDescriptorPoolCreateInfo;

typedef struct VkDescriptorSetAllocateInfo {
    VkStructureType              sType;
    const void*                  pNext;
    VkDescriptorPool             descriptorPool;
    uint32_t                     descriptorSetCount;
    const VkDescriptorSetLayout* pSetLayouts;
} VkDescriptorSetAllocateInfo;

typedef struct VkDescriptorBufferInfo {
    VkBuffer     buffer;
    VkDeviceSize offset;
    VkDeviceSize range;
} VkDescriptorBufferInfo;

typedef struct VkWriteDescriptorSet {
    VkStructureType               sType;
    const void*                   pNext;
    VkDescriptorSet               dstSet;
    uint32_t                      dstBinding;
    uint32_t                      dstArrayElement;
    uint32_t                      descriptorCount;
    uint32_t                      descriptorType;
    const void*                   pImageInfo;
    const VkDescriptorBufferInfo* pBufferInfo;
    const void*                   pTexelBufferView;
} VkWriteDescriptorSet;

typedef struct VkCommandPoolCreateInfo {
    VkStructureType sType;
    const void*     pNext;
    VkFlags         flags;
    uint32_t        queueFamilyIndex;
} VkCommandPoolCreateInfo;

typedef struct VkCommandBufferAllocateInfo {
    VkStructureType sType;
    const void*     pNext;
    VkCommandPool   commandPool;
    uint32_t        level;
    uint32_t        commandBufferCount;
} VkCommandBufferAllocateInfo;

typedef struct VkCommandBufferBeginInfo {
    VkStructureType sType;
    const void*     pNext;
    VkFlags         flags;
    const void*     pInheritanceInfo;
} VkCommandBufferBeginInfo;

typedef struct VkFenceCreateInfo {
    VkStructureType sType;
    const void*     pNext;
    VkFlags         flags;
} VkFenceCreateInfo;

typedef struct VkSubmitInfo {
    VkStructureType        sType;
    const void*            pNext;
    uint32_t               waitSemaphoreCount;
    const VkSemaphore*     pWaitSemaphores;
    const VkFlags*         pWaitDstStageMask;
    uint32_t               commandBufferCount;
    const VkCommandBuffer* pCommandBuffers;
    uint32_t               signalSemaphoreCount;
    const VkSemaphore*     pSignalSemaphores;
} VkSubmitInfo;

typedef struct VkMemoryBarrier {
    VkStructureType sType;
    const void*     pNext;
    VkFlags         srcAccessMask;
    VkFlags         dstAccessMask;
} VkMemoryBarrier;

typedef struct VkBufferCopy {
    VkDeviceSize srcOffset;
    VkDeviceSize dstOffset;
    VkDeviceSize size;
} VkBufferCopy;

/* Phase 2 External Memory / Semaphore Import ABI structs (VK_KHR_external_memory_win32 / fd) */
typedef struct VkExternalMemoryBufferCreateInfo {
    VkStructureType sType;
    const void*     pNext;
    VkFlags         handleTypes;
} VkExternalMemoryBufferCreateInfo;

typedef struct VkImportMemoryFdInfoKHR {
    VkStructureType sType;
    const void*     pNext;
    VkFlags         handleType;
    int             fd;
} VkImportMemoryFdInfoKHR;

typedef struct VkImportMemoryWin32HandleInfoKHR {
    VkStructureType sType;
    const void*     pNext;
    VkFlags         handleType;
    void*           handle;
    const uint16_t* name;
} VkImportMemoryWin32HandleInfoKHR;

/* Function pointer typedefs */
typedef PFN_vkVoidFunction (VKAPI_PTR *PFN_vkGetInstanceProcAddr)(VkInstance, const char*);
typedef PFN_vkVoidFunction (VKAPI_PTR *PFN_vkGetDeviceProcAddr)(VkDevice, const char*);
typedef VkResult (VKAPI_PTR *PFN_vkEnumerateInstanceVersion)(uint32_t*);
typedef VkResult (VKAPI_PTR *PFN_vkCreateInstance)(const VkInstanceCreateInfo*, const VkAllocationCallbacks*, VkInstance*);
typedef void     (VKAPI_PTR *PFN_vkDestroyInstance)(VkInstance, const VkAllocationCallbacks*);
typedef VkResult (VKAPI_PTR *PFN_vkEnumeratePhysicalDevices)(VkInstance, uint32_t*, VkPhysicalDevice*);
typedef void     (VKAPI_PTR *PFN_vkGetPhysicalDeviceProperties)(VkPhysicalDevice, VkPhysicalDeviceProperties*);
typedef void     (VKAPI_PTR *PFN_vkGetPhysicalDeviceQueueFamilyProperties)(VkPhysicalDevice, uint32_t*, VkQueueFamilyProperties*);
typedef void     (VKAPI_PTR *PFN_vkGetPhysicalDeviceMemoryProperties)(VkPhysicalDevice, VkPhysicalDeviceMemoryProperties*);
typedef VkResult (VKAPI_PTR *PFN_vkEnumerateDeviceExtensionProperties)(VkPhysicalDevice, const char*, uint32_t*, VkExtensionProperties*);
typedef VkResult (VKAPI_PTR *PFN_vkCreateDevice)(VkPhysicalDevice, const VkDeviceCreateInfo*, const VkAllocationCallbacks*, VkDevice*);
typedef void     (VKAPI_PTR *PFN_vkDestroyDevice)(VkDevice, const VkAllocationCallbacks*);
typedef void     (VKAPI_PTR *PFN_vkGetDeviceQueue)(VkDevice, uint32_t, uint32_t, VkQueue*);
typedef VkResult (VKAPI_PTR *PFN_vkCreateBuffer)(VkDevice, const VkBufferCreateInfo*, const VkAllocationCallbacks*, VkBuffer*);
typedef void     (VKAPI_PTR *PFN_vkDestroyBuffer)(VkDevice, VkBuffer, const VkAllocationCallbacks*);
typedef void     (VKAPI_PTR *PFN_vkGetBufferMemoryRequirements)(VkDevice, VkBuffer, VkMemoryRequirements*);
typedef VkResult (VKAPI_PTR *PFN_vkAllocateMemory)(VkDevice, const VkMemoryAllocateInfo*, const VkAllocationCallbacks*, VkDeviceMemory*);
typedef void     (VKAPI_PTR *PFN_vkFreeMemory)(VkDevice, VkDeviceMemory, const VkAllocationCallbacks*);
typedef VkResult (VKAPI_PTR *PFN_vkBindBufferMemory)(VkDevice, VkBuffer, VkDeviceMemory, VkDeviceSize);
typedef VkResult (VKAPI_PTR *PFN_vkMapMemory)(VkDevice, VkDeviceMemory, VkDeviceSize, VkDeviceSize, VkFlags, void**);
typedef void     (VKAPI_PTR *PFN_vkUnmapMemory)(VkDevice, VkDeviceMemory);
typedef VkResult (VKAPI_PTR *PFN_vkFlushMappedMemoryRanges)(VkDevice, uint32_t, const VkMappedMemoryRange*);
typedef VkResult (VKAPI_PTR *PFN_vkInvalidateMappedMemoryRanges)(VkDevice, uint32_t, const VkMappedMemoryRange*);
typedef VkResult (VKAPI_PTR *PFN_vkCreateShaderModule)(VkDevice, const VkShaderModuleCreateInfo*, const VkAllocationCallbacks*, VkShaderModule*);
typedef void     (VKAPI_PTR *PFN_vkDestroyShaderModule)(VkDevice, VkShaderModule, const VkAllocationCallbacks*);
typedef VkResult (VKAPI_PTR *PFN_vkCreatePipelineCache)(VkDevice, const VkPipelineCacheCreateInfo*, const VkAllocationCallbacks*, VkPipelineCache*);
typedef void     (VKAPI_PTR *PFN_vkDestroyPipelineCache)(VkDevice, VkPipelineCache, const VkAllocationCallbacks*);
typedef VkResult (VKAPI_PTR *PFN_vkGetPipelineCacheData)(VkDevice, VkPipelineCache, size_t*, void*);
typedef VkResult (VKAPI_PTR *PFN_vkCreateDescriptorSetLayout)(VkDevice, const VkDescriptorSetLayoutCreateInfo*, const VkAllocationCallbacks*, VkDescriptorSetLayout*);
typedef void     (VKAPI_PTR *PFN_vkDestroyDescriptorSetLayout)(VkDevice, VkDescriptorSetLayout, const VkAllocationCallbacks*);
typedef VkResult (VKAPI_PTR *PFN_vkCreatePipelineLayout)(VkDevice, const VkPipelineLayoutCreateInfo*, const VkAllocationCallbacks*, VkPipelineLayout*);
typedef void     (VKAPI_PTR *PFN_vkDestroyPipelineLayout)(VkDevice, VkPipelineLayout, const VkAllocationCallbacks*);
typedef VkResult (VKAPI_PTR *PFN_vkCreateComputePipelines)(VkDevice, VkPipelineCache, uint32_t, const VkComputePipelineCreateInfo*, const VkAllocationCallbacks*, VkPipeline*);
typedef void     (VKAPI_PTR *PFN_vkDestroyPipeline)(VkDevice, VkPipeline, const VkAllocationCallbacks*);
typedef VkResult (VKAPI_PTR *PFN_vkCreateDescriptorPool)(VkDevice, const VkDescriptorPoolCreateInfo*, const VkAllocationCallbacks*, VkDescriptorPool*);
typedef void     (VKAPI_PTR *PFN_vkDestroyDescriptorPool)(VkDevice, VkDescriptorPool, const VkAllocationCallbacks*);
typedef VkResult (VKAPI_PTR *PFN_vkAllocateDescriptorSets)(VkDevice, const VkDescriptorSetAllocateInfo*, VkDescriptorSet*);
typedef void     (VKAPI_PTR *PFN_vkUpdateDescriptorSets)(VkDevice, uint32_t, const VkWriteDescriptorSet*, uint32_t, const void*);
typedef VkResult (VKAPI_PTR *PFN_vkCreateCommandPool)(VkDevice, const VkCommandPoolCreateInfo*, const VkAllocationCallbacks*, VkCommandPool*);
typedef void     (VKAPI_PTR *PFN_vkDestroyCommandPool)(VkDevice, VkCommandPool, const VkAllocationCallbacks*);
typedef VkResult (VKAPI_PTR *PFN_vkAllocateCommandBuffers)(VkDevice, const VkCommandBufferAllocateInfo*, VkCommandBuffer*);
typedef VkResult (VKAPI_PTR *PFN_vkResetCommandBuffer)(VkCommandBuffer, VkFlags);
typedef VkResult (VKAPI_PTR *PFN_vkBeginCommandBuffer)(VkCommandBuffer, const VkCommandBufferBeginInfo*);
typedef VkResult (VKAPI_PTR *PFN_vkEndCommandBuffer)(VkCommandBuffer);
typedef void     (VKAPI_PTR *PFN_vkCmdBindPipeline)(VkCommandBuffer, uint32_t, VkPipeline);
typedef void     (VKAPI_PTR *PFN_vkCmdBindDescriptorSets)(VkCommandBuffer, uint32_t, VkPipelineLayout, uint32_t, uint32_t, const VkDescriptorSet*, uint32_t, const uint32_t*);
typedef void     (VKAPI_PTR *PFN_vkCmdPushConstants)(VkCommandBuffer, VkPipelineLayout, VkFlags, uint32_t, uint32_t, const void*);
typedef void     (VKAPI_PTR *PFN_vkCmdDispatch)(VkCommandBuffer, uint32_t, uint32_t, uint32_t);
typedef void     (VKAPI_PTR *PFN_vkCmdPipelineBarrier)(VkCommandBuffer, VkFlags, VkFlags, VkFlags, uint32_t, const VkMemoryBarrier*, uint32_t, const void*, uint32_t, const void*);
typedef void     (VKAPI_PTR *PFN_vkCmdCopyBuffer)(VkCommandBuffer, VkBuffer, VkBuffer, uint32_t, const VkBufferCopy*);
typedef void     (VKAPI_PTR *PFN_vkCmdFillBuffer)(VkCommandBuffer, VkBuffer, VkDeviceSize, VkDeviceSize, uint32_t);
typedef VkResult (VKAPI_PTR *PFN_vkCreateFence)(VkDevice, const VkFenceCreateInfo*, const VkAllocationCallbacks*, VkFence*);
typedef void     (VKAPI_PTR *PFN_vkDestroyFence)(VkDevice, VkFence, const VkAllocationCallbacks*);
typedef VkResult (VKAPI_PTR *PFN_vkResetFences)(VkDevice, uint32_t, const VkFence*);
typedef VkResult (VKAPI_PTR *PFN_vkWaitForFences)(VkDevice, uint32_t, const VkFence*, VkBool32, uint64_t);
typedef VkResult (VKAPI_PTR *PFN_vkQueueSubmit)(VkQueue, uint32_t, const VkSubmitInfo*, VkFence);
typedef VkResult (VKAPI_PTR *PFN_vkDeviceWaitIdle)(VkDevice);

#ifdef __cplusplus
}
#endif

#endif /* FUJI_VK_ABI_H */
