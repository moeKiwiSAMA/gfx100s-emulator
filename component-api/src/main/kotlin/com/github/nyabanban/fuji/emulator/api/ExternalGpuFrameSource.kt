package com.github.nyabanban.fuji.emulator.api

/**
 * Cross-platform external GPU memory handle types for zero-copy RGB+Depth frame ingestion
 * from external Direct3D 11/12, Vulkan, or Wayland/DRM producers directly into the
 * Vulkan 1.1 Compute optical pipeline without a host-CPU pixel readback round-trip.
 */
enum class ExternalGpuMemoryHandleType(val nativeCode: Int) {
    /** No external handle (sentinel / host-memory fallback). */
    NONE(0),

    /** Windows NT handle (`VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT`, DX12 / Vulkan / DX11 NT). */
    WIN32_NT_HANDLE(1),

    /** Windows global KMT handle (`VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_KMT_BIT`, legacy DX11). */
    WIN32_KMT_HANDLE(2),

    /** Windows DXGI / D3D11 shared texture NT handle (`VK_EXTERNAL_MEMORY_HANDLE_TYPE_D3D11_TEXTURE_BIT`). */
    WIN32_D3D11_TEXTURE(3),

    /** Linux POSIX opaque file descriptor (`VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT`). */
    OPAQUE_FD(4),

    /** Linux DRM/GBM/Wayland DMA-BUF file descriptor (`VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT`). */
    DMA_BUF_FD(5),
    ;

    val isWin32: Boolean
        get() = this == WIN32_NT_HANDLE || this == WIN32_KMT_HANDLE || this == WIN32_D3D11_TEXTURE

    val isPosixFd: Boolean
        get() = this == OPAQUE_FD || this == DMA_BUF_FD
}

/**
 * Supported Vulkan external color surface formats (`VkFormat` numeric codes).
 */
enum class ExternalGpuColorFormat(val vkFormat: Int, val bytesPerPixel: Int) {
    RGBA8_UNORM(37, 4),
    RGBA8_SRGB(43, 4),
    BGRA8_UNORM(44, 4),
    BGRA8_SRGB(50, 4),
    RGBA16_SFLOAT(97, 8),
    RGBA32_SFLOAT(109, 16),
}

/**
 * Supported Vulkan external depth / Z-buffer surface formats (`VkFormat` numeric codes).
 */
enum class ExternalGpuDepthFormat(val vkFormat: Int, val bytesPerPixel: Int) {
    NONE(0, 0),
    D32_SFLOAT(126, 4),
    R32_SFLOAT(100, 4),
    D24_UNORM_S8_UINT(129, 4),
    D32_SFLOAT_S8_UINT(130, 5),
}

/**
 * Immutable descriptor for a zero-copy external GPU RGB+Depth frame backed by an OS-native
 * GPU memory handle (`WIN32_NT_HANDLE` / `WIN32_KMT_HANDLE` / `WIN32_D3D11_TEXTURE` /
 * `OPAQUE_FD` / `DMA_BUF_FD`) and optional external synchronization semaphores.
 */
data class ExternalGpuFrameDescriptor(
    val handleType: ExternalGpuMemoryHandleType,
    val width: Int,
    val height: Int,
    val colorFormat: ExternalGpuColorFormat,
    val colorHandle: Long,
    val colorMemorySizeBytes: Long,
    val colorPitchBytes: Int = 0,
    val depthFormat: ExternalGpuDepthFormat = ExternalGpuDepthFormat.NONE,
    val depthHandle: Long = 0L,
    val depthMemorySizeBytes: Long = 0L,
    val depthPitchBytes: Int = 0,
    val waitSemaphoreHandle: Long = 0L,
    val signalSemaphoreHandle: Long = 0L,
    val timelineWaitValue: Long = 0L,
    val timelineSignalValue: Long = 0L,
    val nearClipMeters: Float = 0.1f,
    val farClipMeters: Float = 1000.0f,
    val reversedZ: Boolean = false,
    val frameSequence: Long = 0L,
    val flags: Int = 0,
) {
    val hasDepthBuffer: Boolean
        get() = depthFormat != ExternalGpuDepthFormat.NONE && depthMemorySizeBytes > 0L &&
            (if (handleType.isPosixFd) depthHandle >= 0L else depthHandle != 0L)

    val hasExternalSync: Boolean
        get() = waitSemaphoreHandle != 0L || signalSemaphoreHandle != 0L

    /** Validates structural invariants before handing the descriptor to the native Vulkan bridge. */
    fun validate(): List<String> {
        val errors = ArrayList<String>()
        if (handleType == ExternalGpuMemoryHandleType.NONE) {
            errors.add("handleType must not be NONE")
        }
        if (width <= 0 || height <= 0) {
            errors.add("dimensions must be positive (got ${width}x$height)")
        }
        if (colorMemorySizeBytes <= 0L) {
            errors.add("colorMemorySizeBytes must be positive (got $colorMemorySizeBytes)")
        }
        if (handleType.isWin32 && colorHandle == 0L) {
            errors.add("Win32 colorHandle must be non-zero")
        }
        if (handleType.isPosixFd && colorHandle < 0L) {
            errors.add("POSIX colorHandle fd must be non-negative (got $colorHandle)")
        }
        if (depthFormat != ExternalGpuDepthFormat.NONE) {
            if (depthMemorySizeBytes <= 0L) {
                errors.add("depthMemorySizeBytes must be positive when depthFormat=$depthFormat")
            }
            if (handleType.isWin32 && depthHandle == 0L) {
                errors.add("Win32 depthHandle must be non-zero when depthFormat=$depthFormat")
            }
            if (handleType.isPosixFd && depthHandle < 0L) {
                errors.add("POSIX depthHandle fd must be non-negative when depthFormat=$depthFormat")
            }
        }
        if (nearClipMeters <= 0f || farClipMeters <= nearClipMeters) {
            errors.add("invalid depth clip range [$nearClipMeters, $farClipMeters]")
        }
        return errors
    }
}

/**
 * Service port (`scene.externalGpuFrame`) implemented by zero-copy game/window capture bridges
 * (DX11/DX12 Shared Texture, Vulkan External Memory, or Linux DMA-BUF) to feed live GPU RGB +
 * Z-Buffer surfaces directly into the optical compute backend.
 */
interface ExternalGpuFrameSource : AutoCloseable {
    companion object {
        const val PORT_NAME = "scene.externalGpuFrame"
    }

    /** Stable human-readable identifier of the producer (e.g. `dxgi-shared-tex`, `vulkan-dmabuf`). */
    val sourceId: String

    /** Returns `true` when the producer has an active external GPU surface ready to acquire. */
    fun isAvailable(): Boolean

    /** Acquires the latest GPU frame descriptor, or `null` if no new frame is available. */
    fun acquireLatestFrame(): ExternalGpuFrameDescriptor?

    /** Signals to the producer that the consumer has finished reading [frame]. */
    fun releaseFrame(frame: ExternalGpuFrameDescriptor)
}
