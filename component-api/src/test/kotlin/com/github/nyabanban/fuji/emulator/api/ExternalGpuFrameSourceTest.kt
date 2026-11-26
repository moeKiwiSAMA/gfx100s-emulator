package com.github.nyabanban.fuji.emulator.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalGpuFrameSourceTest {

    @Test
    fun descriptorValidatesWin32AndLinuxExternalHandlesAndDepthConfig() {
        val validWin32 = ExternalGpuFrameDescriptor(
            handleType = ExternalGpuMemoryHandleType.WIN32_NT_HANDLE,
            width = 1920,
            height = 1080,
            colorFormat = ExternalGpuColorFormat.RGBA16_SFLOAT,
            colorHandle = 0x1234L,
            colorMemorySizeBytes = 1920L * 1080L * 8L,
            depthFormat = ExternalGpuDepthFormat.D32_SFLOAT,
            depthHandle = 0x5678L,
            depthMemorySizeBytes = 1920L * 1080L * 4L,
            waitSemaphoreHandle = 0x9ABCL,
            timelineWaitValue = 42L,
            reversedZ = true,
        )
        assertTrue(validWin32.validate().isEmpty())
        assertTrue(validWin32.hasDepthBuffer)
        assertTrue(validWin32.hasExternalSync)
        assertTrue(validWin32.handleType.isWin32)
        assertFalse(validWin32.handleType.isPosixFd)

        val validDmaBuf = ExternalGpuFrameDescriptor(
            handleType = ExternalGpuMemoryHandleType.DMA_BUF_FD,
            width = 1280,
            height = 720,
            colorFormat = ExternalGpuColorFormat.BGRA8_UNORM,
            colorHandle = 11L,
            colorMemorySizeBytes = 1280L * 720L * 4L,
            depthFormat = ExternalGpuDepthFormat.R32_SFLOAT,
            depthHandle = 12L,
            depthMemorySizeBytes = 1280L * 720L * 4L,
        )
        assertTrue(validDmaBuf.validate().isEmpty())
        assertTrue(validDmaBuf.hasDepthBuffer)
        assertFalse(validDmaBuf.hasExternalSync)
        assertTrue(validDmaBuf.handleType.isPosixFd)

        val invalid = ExternalGpuFrameDescriptor(
            handleType = ExternalGpuMemoryHandleType.NONE,
            width = 0,
            height = -1,
            colorFormat = ExternalGpuColorFormat.RGBA8_UNORM,
            colorHandle = 0L,
            colorMemorySizeBytes = 0L,
            depthFormat = ExternalGpuDepthFormat.D32_SFLOAT,
            depthHandle = 0L,
            depthMemorySizeBytes = 0L,
            nearClipMeters = 10f,
            farClipMeters = 5f,
        )
        val errs = invalid.validate()
        assertTrue(errs.size >= 4, "Expected multiple validation errors, got: $errs")
        assertEquals("scene.externalGpuFrame", ExternalGpuFrameSource.PORT_NAME)
    }
}
