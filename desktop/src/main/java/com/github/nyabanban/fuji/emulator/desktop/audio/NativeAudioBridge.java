package com.github.nyabanban.fuji.emulator.desktop.audio;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JDK 25 Foreign Function &amp; Memory (FFM) binding for the native Fuji audio bridge
 * ({@code libfuji_audio.so} on Linux PulseAudio/PipeWire, {@code fuji_audio.dll} on Windows WASAPI).
 *
 * <p>Implements {@code tools/native/audio-bridge/fuji_audio.h} ABI version {@value #ABI_VERSION}.
 * Leaf metadata/latency calls use {@link Linker.Option#critical(boolean) Linker.Option.critical(false)},
 * while device enumeration, stream open/close, and blocking PCM I/O use standard FFM downcalls.
 */
public final class NativeAudioBridge implements AutoCloseable {

    public static final int ABI_VERSION = 1;

    public static final int DIRECTION_OUTPUT = 0;
    public static final int DIRECTION_INPUT = 1;
    public static final int DIRECTION_LOOPBACK = 2;

    public static final int DEVICE_ID_MAX = 256;
    public static final int DEVICE_NAME_MAX = 256;
    public static final int DEVICE_INFO_SIZE_BYTES = 524;
    public static final int DEFAULT_MAX_ENUMERATE_DEVICES = 64;

    private static final long OFFSET_ID = 0L;
    private static final long OFFSET_NAME = 256L;
    private static final long OFFSET_IS_DEFAULT = 512L;
    private static final long OFFSET_SAMPLE_RATE_HZ = 516L;
    private static final long OFFSET_CHANNELS = 520L;

    public record DeviceInfo(
        String id,
        String name,
        boolean isDefault,
        int sampleRateHz,
        int channels
    ) {
        public DeviceInfo {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
        }
    }

    private final Path libraryPath;
    private final Arena libraryArena;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Set<AutoCloseable> activeStreams = ConcurrentHashMap.newKeySet();

    private final MethodHandle fnAbiVersion;
    private final MethodHandle fnBackendName;
    private final MethodHandle fnEnumerate;
    private final MethodHandle fnOpenOutput;
    private final MethodHandle fnOpenInput;
    private final MethodHandle fnWrite;
    private final MethodHandle fnRead;
    private final MethodHandle fnGetLatencyMs;
    private final MethodHandle fnClose;

    private NativeAudioBridge(Path libraryPath, Arena libraryArena, SymbolLookup lookup) {
        this.libraryPath = libraryPath;
        this.libraryArena = libraryArena;

        Linker linker = Linker.nativeLinker();
        Linker.Option criticalLeaf = Linker.Option.critical(false);

        this.fnAbiVersion = linker.downcallHandle(
            requireSymbol(lookup, "fuji_audio_abi_version"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT),
            criticalLeaf
        );
        this.fnBackendName = linker.downcallHandle(
            requireSymbol(lookup, "fuji_audio_backend_name"),
            FunctionDescriptor.of(ValueLayout.ADDRESS),
            criticalLeaf
        );
        this.fnEnumerate = linker.downcallHandle(
            requireSymbol(lookup, "fuji_audio_enumerate"),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT
            )
        );
        this.fnOpenOutput = linker.downcallHandle(
            requireSymbol(lookup, "fuji_audio_open_output"),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT
            )
        );
        this.fnOpenInput = linker.downcallHandle(
            requireSymbol(lookup, "fuji_audio_open_input"),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT
            )
        );
        this.fnWrite = linker.downcallHandle(
            requireSymbol(lookup, "fuji_audio_write"),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT
            )
        );
        this.fnRead = linker.downcallHandle(
            requireSymbol(lookup, "fuji_audio_read"),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT
            )
        );
        this.fnGetLatencyMs = linker.downcallHandle(
            requireSymbol(lookup, "fuji_audio_get_latency_ms"),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            criticalLeaf
        );
        this.fnClose = linker.downcallHandle(
            requireSymbol(lookup, "fuji_audio_close"),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
        );
    }

    public static Optional<Path> findDefaultLibrary() {
        return findDefaultLibrary(null);
    }

    public static Optional<Path> findDefaultLibrary(Path runtimeHome) {
        boolean isWindows = isWindowsHost();
        String fileName = isWindows ? "fuji_audio.dll" : "libfuji_audio.so";
        String buildSubdir = isWindows ? "build-win64" : "build-linux";

        Set<Path> candidates = new LinkedHashSet<>();

        addIfPresent(candidates, System.getProperty("fuji.audio.library"));
        addIfPresent(candidates, System.getenv("FUJI_AUDIO_LIBRARY"));
        if (isWindows) {
            addIfPresent(candidates, System.getenv("FUJI_WIN64_AUDIO_DLL"));
        }

        if (runtimeHome != null) {
            Path normalizedHome = runtimeHome.toAbsolutePath().normalize();
            candidates.add(normalizedHome.resolve("runtime").resolve("native").resolve(fileName));
            candidates.add(normalizedHome.resolve("native").resolve(fileName));
            candidates.add(normalizedHome.resolve(".tools").resolve("audio-bridge").resolve(buildSubdir).resolve(fileName));
            candidates.add(normalizedHome.resolve(".tools").resolve("audio-bridge").resolve("build").resolve(fileName));
        }

        Path cwd = Path.of("").toAbsolutePath().normalize();
        candidates.add(cwd.resolve("runtime").resolve("native").resolve(fileName));
        candidates.add(cwd.resolve(".tools").resolve("audio-bridge").resolve(buildSubdir).resolve(fileName));
        candidates.add(cwd.resolve(".tools").resolve("audio-bridge").resolve("build").resolve(fileName));

        if (!isWindows) {
            candidates.add(Path.of("/opt/fuji-cache/audio-bridge").resolve(fileName));
        }

        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    public static NativeAudioBridge load(Path libraryPath) {
        Objects.requireNonNull(libraryPath, "libraryPath");
        Path normalized = libraryPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Native audio bridge library not found: " + normalized);
        }
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup(normalized, arena);
            NativeAudioBridge bridge = new NativeAudioBridge(normalized, arena, lookup);
            int abi = bridge.abiVersion();
            if (abi != ABI_VERSION) {
                arena.close();
                throw new IllegalStateException(
                    "Native audio bridge ABI mismatch at " + normalized
                        + ": expected " + ABI_VERSION + ", got " + abi
                );
            }
            return bridge;
        } catch (RuntimeException e) {
            if (arena.scope().isAlive()) {
                arena.close();
            }
            throw e;
        } catch (Throwable t) {
            if (arena.scope().isAlive()) {
                arena.close();
            }
            throw new IllegalStateException("Failed to load native audio bridge from " + normalized, t);
        }
    }

    public static Optional<NativeAudioBridge> tryLoad(Path libraryPath) {
        if (libraryPath == null || !Files.isRegularFile(libraryPath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(load(libraryPath));
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    public static Optional<NativeAudioBridge> tryLoadDefault() {
        return tryLoadDefault(null);
    }

    public static Optional<NativeAudioBridge> tryLoadDefault(Path runtimeHome) {
        return findDefaultLibrary(runtimeHome).flatMap(NativeAudioBridge::tryLoad);
    }

    public Path libraryPath() {
        return libraryPath;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public int abiVersion() {
        ensureOpen();
        try {
            return (int) fnAbiVersion.invokeExact();
        } catch (Throwable t) {
            throw propagate("fuji_audio_abi_version", t);
        }
    }

    public String backendName() {
        ensureOpen();
        try {
            MemorySegment ptr = (MemorySegment) fnBackendName.invokeExact();
            if (ptr == null || ptr.address() == 0L) {
                return "unknown";
            }
            return readBoundedCString(ptr.reinterpret(64L), 64);
        } catch (Throwable t) {
            throw propagate("fuji_audio_backend_name", t);
        }
    }

    public List<DeviceInfo> enumerate(int direction) {
        return enumerate(direction, DEFAULT_MAX_ENUMERATE_DEVICES);
    }

    public List<DeviceInfo> enumerate(int direction, int maxCount) {
        ensureOpen();
        if (maxCount <= 0) {
            return List.of();
        }
        long byteSize = (long) maxCount * DEVICE_INFO_SIZE_BYTES;
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment buffer = callArena.allocate(byteSize, 4L);
            int count = (int) fnEnumerate.invokeExact(direction, buffer, maxCount);
            if (count <= 0) {
                return List.of();
            }
            int clamped = Math.min(count, maxCount);
            List<DeviceInfo> devices = new ArrayList<>(clamped);
            for (int i = 0; i < clamped; i++) {
                long base = (long) i * DEVICE_INFO_SIZE_BYTES;
                MemorySegment entry = buffer.asSlice(base, DEVICE_INFO_SIZE_BYTES);
                String id = readBoundedCString(entry.asSlice(OFFSET_ID, DEVICE_ID_MAX), DEVICE_ID_MAX);
                String name = readBoundedCString(entry.asSlice(OFFSET_NAME, DEVICE_NAME_MAX), DEVICE_NAME_MAX);
                boolean isDefault = entry.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_IS_DEFAULT) != 0;
                int sampleRateHz = entry.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_SAMPLE_RATE_HZ);
                int channels = entry.get(ValueLayout.JAVA_INT_UNALIGNED, OFFSET_CHANNELS);
                devices.add(new DeviceInfo(id, name, isDefault, sampleRateHz, channels));
            }
            return Collections.unmodifiableList(devices);
        } catch (Throwable t) {
            throw propagate("fuji_audio_enumerate", t);
        }
    }

    /**
     * Opens a native playback stream, or returns {@code null} if the host audio device is unavailable.
     */
    public OutputStream openOutput(String deviceId, int sampleRateHz, int channels, int targetLatencyMs) {
        ensureOpen();
        if (sampleRateHz <= 0 || channels <= 0) {
            return null;
        }
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment idSeg = allocateOptionalUtf8(callArena, deviceId);
            MemorySegment handle = (MemorySegment) fnOpenOutput.invokeExact(
                idSeg,
                sampleRateHz,
                channels,
                targetLatencyMs
            );
            if (handle == null || handle.address() == 0L) {
                return null;
            }
            OutputStream stream = new OutputStream(handle, sampleRateHz, channels);
            activeStreams.add(stream);
            return stream;
        } catch (Throwable t) {
            throw propagate("fuji_audio_open_output", t);
        }
    }

    /**
     * Opens a native capture or loopback stream, or returns {@code null} if the host audio device is unavailable.
     */
    public InputStream openInput(
        String deviceId,
        boolean loopback,
        int sampleRateHz,
        int channels,
        int targetLatencyMs
    ) {
        ensureOpen();
        if (sampleRateHz <= 0 || channels <= 0) {
            return null;
        }
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment idSeg = allocateOptionalUtf8(callArena, deviceId);
            MemorySegment handle = (MemorySegment) fnOpenInput.invokeExact(
                idSeg,
                loopback ? 1 : 0,
                sampleRateHz,
                channels,
                targetLatencyMs
            );
            if (handle == null || handle.address() == 0L) {
                return null;
            }
            InputStream stream = new InputStream(handle, loopback, sampleRateHz, channels);
            activeStreams.add(stream);
            return stream;
        } catch (Throwable t) {
            throw propagate("fuji_audio_open_input", t);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<AutoCloseable> remaining = new ArrayList<>(activeStreams);
        for (AutoCloseable stream : remaining) {
            try {
                stream.close();
            } catch (Exception ignored) {
                // Best-effort cleanup before unloading native library arena.
            }
        }
        activeStreams.clear();
        if (libraryArena.scope().isAlive()) {
            libraryArena.close();
        }
    }

    private void invokeCloseHandle(MemorySegment handle) {
        try {
            fnClose.invokeExact(handle);
        } catch (Throwable t) {
            throw propagate("fuji_audio_close", t);
        }
    }

    private int invokeGetLatencyMs(MemorySegment handle) {
        try {
            return (int) fnGetLatencyMs.invokeExact(handle);
        } catch (Throwable t) {
            throw propagate("fuji_audio_get_latency_ms", t);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("NativeAudioBridge is closed");
        }
    }

    private static MemorySegment requireSymbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() ->
            new UnsatisfiedLinkError("Missing required native audio symbol: " + name)
        );
    }

    private static MemorySegment allocateOptionalUtf8(Arena arena, String value) {
        if (value == null || value.isEmpty()) {
            return MemorySegment.NULL;
        }
        return arena.allocateFrom(value, StandardCharsets.UTF_8);
    }

    private static String readBoundedCString(MemorySegment segment, int maxBytes) {
        int len = 0;
        while (len < maxBytes && segment.get(ValueLayout.JAVA_BYTE, len) != 0) {
            len++;
        }
        if (len == 0) {
            return "";
        }
        byte[] bytes = new byte[len];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, 0L, bytes, 0, len);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void addIfPresent(Set<Path> target, String rawPath) {
        if (rawPath != null && !rawPath.isBlank()) {
            target.add(Path.of(rawPath.trim()));
        }
    }

    private static boolean isWindowsHost() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private static RuntimeException propagate(String symbol, Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error err) {
            throw err;
        }
        return new IllegalStateException("Native audio FFM call failed: " + symbol, t);
    }

    public final class OutputStream implements AutoCloseable {
        private final MemorySegment handle;
        private final int sampleRateHz;
        private final int channels;
        private final AtomicBoolean streamClosed = new AtomicBoolean(false);
        private final Object ioLock = new Object();
        private Arena stagingArena;
        private MemorySegment stagingBuffer = MemorySegment.NULL;
        private int stagingCapacitySamples;

        private OutputStream(MemorySegment handle, int sampleRateHz, int channels) {
            this.handle = handle;
            this.sampleRateHz = sampleRateHz;
            this.channels = channels;
        }

        public int sampleRateHz() {
            return sampleRateHz;
        }

        public int channels() {
            return channels;
        }

        public boolean isClosed() {
            return streamClosed.get();
        }

        public int latencyMs() {
            if (streamClosed.get()) {
                return -1;
            }
            return invokeGetLatencyMs(handle);
        }

        public int write(short[] interleavedPcm, int frames) {
            return write(interleavedPcm, 0, frames);
        }

        public int write(short[] interleavedPcm, int offsetSamples, int frames) {
            Objects.requireNonNull(interleavedPcm, "interleavedPcm");
            if (frames <= 0) {
                return 0;
            }
            int sampleCount = Math.multiplyExact(frames, channels);
            if (offsetSamples < 0 || offsetSamples + sampleCount > interleavedPcm.length) {
                throw new IndexOutOfBoundsException(
                    "PCM buffer range out of bounds: offset=" + offsetSamples
                        + ", samples=" + sampleCount + ", length=" + interleavedPcm.length
                );
            }
            synchronized (ioLock) {
                if (streamClosed.get()) {
                    return -1;
                }
                ensureStagingCapacity(sampleCount);
                MemorySegment.copy(interleavedPcm, offsetSamples, stagingBuffer, ValueLayout.JAVA_SHORT, 0L, sampleCount);
                try {
                    return (int) fnWrite.invokeExact(handle, stagingBuffer, frames);
                } catch (Throwable t) {
                    throw propagate("fuji_audio_write", t);
                }
            }
        }

        public int write(MemorySegment nativeInterleavedPcm, int frames) {
            Objects.requireNonNull(nativeInterleavedPcm, "nativeInterleavedPcm");
            if (frames <= 0) {
                return 0;
            }
            long requiredBytes = (long) frames * channels * Short.BYTES;
            if (nativeInterleavedPcm.byteSize() < requiredBytes) {
                throw new IndexOutOfBoundsException(
                    "Native PCM segment too small: need " + requiredBytes + " bytes, got " + nativeInterleavedPcm.byteSize()
                );
            }
            synchronized (ioLock) {
                if (streamClosed.get()) {
                    return -1;
                }
                try {
                    return (int) fnWrite.invokeExact(handle, nativeInterleavedPcm, frames);
                } catch (Throwable t) {
                    throw propagate("fuji_audio_write", t);
                }
            }
        }

        @Override
        public void close() {
            if (!streamClosed.compareAndSet(false, true)) {
                return;
            }
            activeStreams.remove(this);
            synchronized (ioLock) {
                try {
                    invokeCloseHandle(handle);
                } finally {
                    if (stagingArena != null && stagingArena.scope().isAlive()) {
                        stagingArena.close();
                    }
                    stagingArena = null;
                    stagingBuffer = MemorySegment.NULL;
                    stagingCapacitySamples = 0;
                }
            }
        }

        private void ensureStagingCapacity(int samples) {
            if (samples <= stagingCapacitySamples && stagingArena != null && stagingArena.scope().isAlive()) {
                return;
            }
            if (stagingArena != null && stagingArena.scope().isAlive()) {
                stagingArena.close();
            }
            int nextCapacity = Math.max(samples, Math.max(stagingCapacitySamples * 2, 2048));
            stagingArena = Arena.ofShared();
            stagingBuffer = stagingArena.allocate((long) nextCapacity * Short.BYTES, Short.BYTES);
            stagingCapacitySamples = nextCapacity;
        }
    }

    public final class InputStream implements AutoCloseable {
        private final MemorySegment handle;
        private final boolean loopback;
        private final int sampleRateHz;
        private final int channels;
        private final AtomicBoolean streamClosed = new AtomicBoolean(false);
        private final Object ioLock = new Object();
        private Arena stagingArena;
        private MemorySegment stagingBuffer = MemorySegment.NULL;
        private int stagingCapacitySamples;

        private InputStream(MemorySegment handle, boolean loopback, int sampleRateHz, int channels) {
            this.handle = handle;
            this.loopback = loopback;
            this.sampleRateHz = sampleRateHz;
            this.channels = channels;
        }

        public boolean isLoopback() {
            return loopback;
        }

        public int sampleRateHz() {
            return sampleRateHz;
        }

        public int channels() {
            return channels;
        }

        public boolean isClosed() {
            return streamClosed.get();
        }

        public int latencyMs() {
            if (streamClosed.get()) {
                return -1;
            }
            return invokeGetLatencyMs(handle);
        }

        public int read(short[] interleavedPcm, int maxFrames, int timeoutMs) {
            return read(interleavedPcm, 0, maxFrames, timeoutMs);
        }

        public int read(short[] interleavedPcm, int offsetSamples, int maxFrames, int timeoutMs) {
            Objects.requireNonNull(interleavedPcm, "interleavedPcm");
            if (maxFrames <= 0) {
                return 0;
            }
            int maxSamples = Math.multiplyExact(maxFrames, channels);
            if (offsetSamples < 0 || offsetSamples + maxSamples > interleavedPcm.length) {
                throw new IndexOutOfBoundsException(
                    "PCM buffer range out of bounds: offset=" + offsetSamples
                        + ", samples=" + maxSamples + ", length=" + interleavedPcm.length
                );
            }
            synchronized (ioLock) {
                if (streamClosed.get()) {
                    return -1;
                }
                ensureStagingCapacity(maxSamples);
                int framesRead;
                try {
                    framesRead = (int) fnRead.invokeExact(handle, stagingBuffer, maxFrames, timeoutMs);
                } catch (Throwable t) {
                    throw propagate("fuji_audio_read", t);
                }
                if (framesRead > 0) {
                    int samplesRead = Math.min(framesRead, maxFrames) * channels;
                    MemorySegment.copy(stagingBuffer, ValueLayout.JAVA_SHORT, 0L, interleavedPcm, offsetSamples, samplesRead);
                }
                return framesRead;
            }
        }

        public int read(MemorySegment nativeInterleavedPcm, int maxFrames, int timeoutMs) {
            Objects.requireNonNull(nativeInterleavedPcm, "nativeInterleavedPcm");
            if (maxFrames <= 0) {
                return 0;
            }
            long requiredBytes = (long) maxFrames * channels * Short.BYTES;
            if (nativeInterleavedPcm.byteSize() < requiredBytes) {
                throw new IndexOutOfBoundsException(
                    "Native PCM segment too small: need " + requiredBytes + " bytes, got " + nativeInterleavedPcm.byteSize()
                );
            }
            synchronized (ioLock) {
                if (streamClosed.get()) {
                    return -1;
                }
                try {
                    return (int) fnRead.invokeExact(handle, nativeInterleavedPcm, maxFrames, timeoutMs);
                } catch (Throwable t) {
                    throw propagate("fuji_audio_read", t);
                }
            }
        }

        @Override
        public void close() {
            if (!streamClosed.compareAndSet(false, true)) {
                return;
            }
            activeStreams.remove(this);
            synchronized (ioLock) {
                try {
                    invokeCloseHandle(handle);
                } finally {
                    if (stagingArena != null && stagingArena.scope().isAlive()) {
                        stagingArena.close();
                    }
                    stagingArena = null;
                    stagingBuffer = MemorySegment.NULL;
                    stagingCapacitySamples = 0;
                }
            }
        }

        private void ensureStagingCapacity(int samples) {
            if (samples <= stagingCapacitySamples && stagingArena != null && stagingArena.scope().isAlive()) {
                return;
            }
            if (stagingArena != null && stagingArena.scope().isAlive()) {
                stagingArena.close();
            }
            int nextCapacity = Math.max(samples, Math.max(stagingCapacitySamples * 2, 2048));
            stagingArena = Arena.ofShared();
            stagingBuffer = stagingArena.allocate((long) nextCapacity * Short.BYTES, Short.BYTES);
            stagingCapacitySamples = nextCapacity;
        }
    }
}
