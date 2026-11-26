package com.github.nyabanban.fuji.emulator.desktop.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class NativeAudioBridgeTest {

    @Test
    void missingLibraryReturnsEmptyOptional() {
        Optional<NativeAudioBridge> missing = NativeAudioBridge.tryLoad(Path.of("nonexistent-fuji-audio-lib.so"));
        assertTrue(missing.isEmpty());
    }

    @Test
    void loadsBuiltNativeAudioLibraryAndEnumeratesGracefully() {
        Path projectRoot = resolveProjectRoot();
        Optional<Path> discovered = NativeAudioBridge.findDefaultLibrary(projectRoot);
        Assumptions.assumeTrue(
            discovered.isPresent() && Files.isRegularFile(discovered.get()),
            "Native audio bridge binary not built yet; run :buildAudioNative first."
        );

        try (NativeAudioBridge bridge = NativeAudioBridge.load(discovered.get())) {
            assertEquals(NativeAudioBridge.ABI_VERSION, bridge.abiVersion());

            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                assertEquals("wasapi", bridge.backendName());
            } else {
                assertEquals("pulse-pipewire", bridge.backendName());
            }

            List<NativeAudioBridge.DeviceInfo> outputs = bridge.enumerate(NativeAudioBridge.DIRECTION_OUTPUT);
            List<NativeAudioBridge.DeviceInfo> inputs = bridge.enumerate(NativeAudioBridge.DIRECTION_INPUT);
            List<NativeAudioBridge.DeviceInfo> loopbacks = bridge.enumerate(NativeAudioBridge.DIRECTION_LOOPBACK);
            assertNotNull(outputs);
            assertNotNull(inputs);
            assertNotNull(loopbacks);

            // Invalid arguments must safely return null rather than throwing or crashing native code.
            assertNull(bridge.openOutput(null, 0, 2, 20));
            assertNull(bridge.openInput(null, false, 48000, 0, 20));
            assertTrue(bridge.enumerate(99).isEmpty());
            assertTrue(bridge.enumerate(NativeAudioBridge.DIRECTION_OUTPUT, 0).isEmpty());
            assertFalse(bridge.isClosed());
        }
    }

    @Test
    void closedBridgeRejectsSubsequentCalls() {
        Path projectRoot = resolveProjectRoot();
        Optional<Path> discovered = NativeAudioBridge.findDefaultLibrary(projectRoot);
        Assumptions.assumeTrue(
            discovered.isPresent() && Files.isRegularFile(discovered.get()),
            "Native audio bridge binary not built yet."
        );

        NativeAudioBridge bridge = NativeAudioBridge.load(discovered.get());
        bridge.close();
        assertTrue(bridge.isClosed());
        // Idempotent close must not throw.
        bridge.close();
        assertThrows(IllegalStateException.class, bridge::abiVersion);
    }

    private static Path resolveProjectRoot() {
        String configured = System.getProperty("componentProjectRoot");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }
}
