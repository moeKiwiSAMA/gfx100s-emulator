package com.github.nyabanban.fuji.emulator.api

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeCodecBridgeTest {
    @Test
    fun resolvesExplicitAndSiblingLibraryPathsForLinuxAndWin64() {
        val tempRoot = Files.createTempDirectory("codec-bridge-resolve-")
        try {
            val linuxDir = Files.createDirectories(tempRoot.resolve(".tools/codec-bridge/build-linux"))
            val winDir = Files.createDirectories(tempRoot.resolve(".tools/codec-bridge/build-win64"))
            val siblingDir = Files.createDirectories(tempRoot.resolve("runtime/native"))

            val linuxSo = Files.writeString(linuxDir.resolve("libfuji_codec.so"), "elf")
            val winDll = Files.writeString(winDir.resolve("fuji_codec.dll"), "pe")
            val siblingSo = Files.writeString(siblingDir.resolve("libfuji_codec.so"), "elf")
            val siblingRender = Files.writeString(siblingDir.resolve("libfuji_render.so"), "elf")

            val explicitLinux = NativeCodecBridge.resolveLibraryPath(
                isWindowsOverride = false,
                envProvider = { k -> if (k == "FUJI_CODEC_LIBRARY") linuxSo.toString() else null },
                propProvider = { null },
            )
            assertEquals(linuxSo.toAbsolutePath().normalize(), explicitLinux)

            val explicitWin = NativeCodecBridge.resolveLibraryPath(
                isWindowsOverride = true,
                envProvider = { k -> if (k == "FUJI_WIN64_CODEC_DLL") winDll.toString() else null },
                propProvider = { null },
            )
            assertEquals(winDll.toAbsolutePath().normalize(), explicitWin)

            val viaSibling = NativeCodecBridge.resolveLibraryPath(
                isWindowsOverride = false,
                envProvider = { k -> if (k == "FUJI_RENDER_LIBRARY") siblingRender.toString() else null },
                propProvider = { null },
            )
            assertEquals(siblingSo.toAbsolutePath().normalize(), viaSibling)
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun loadsNativeCodecLibraryAndReportsCapabilities() {
        assertTrue(
            NativeCodecBridge.isAvailable,
            "Expected NativeCodecBridge to load libfuji_codec: ${NativeCodecBridge.lastLoadError}",
        )
        assertTrue(NativeCodecBridge.hasLsraw, "Expected native multi-threaded LSRAW capability")
        assertNotNull(NativeCodecBridge.libraryPath)
        assertTrue(NativeCodecBridge.defaultThreads >= 1)
        assertTrue(NativeCodecBridge.backendName.startsWith("native-mt-"))
    }
}
