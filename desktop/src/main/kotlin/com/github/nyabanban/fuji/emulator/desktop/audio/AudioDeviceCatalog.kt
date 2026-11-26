package com.github.nyabanban.fuji.emulator.desktop.audio

import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** Preferred host audio backend routing mode. */
enum class AudioBackendPreference {
    AUTO,
    NATIVE,
    JAVASOUND;

    companion object {
        fun parse(raw: String): AudioBackendPreference =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Invalid backendPreference '$raw'; expected one of ${entries.joinToString(", ") { it.name }}",
                )
    }
}

/** Audio input source mode feeding the emulated GFX100S I2S RX pipeline. */
enum class AudioInputMode {
    DISABLED,
    HOST_MIC,
    OUTPUT_LOOPBACK,
    TEST_TONE,
    SCENE_AUDIO;

    companion object {
        fun parse(raw: String): AudioInputMode =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Invalid inputMode '$raw'; expected one of ${entries.joinToString(", ") { it.name }}",
                )
    }
}

/** Enumeration direction matching `fuji_audio.h` (`0 = OUTPUT`, `1 = INPUT`, `2 = LOOPBACK`). */
enum class AudioDeviceDirection(val nativeCode: Int) {
    OUTPUT(0),
    INPUT(1),
    LOOPBACK(2),
}

/** Host audio endpoint descriptor surfaced to the GUI Audio I/O panel, CLI, and `/api/audio/devices`. */
data class AudioDeviceDescriptor(
    val id: String,
    val name: String,
    val isDefault: Boolean,
    val backend: String,
    val sampleRateHz: Int = HostAudioPlayback.SAMPLE_RATE_HZ,
    val channels: Int = HostAudioPlayback.CHANNELS,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "isDefault" to isDefault,
        "backend" to backend,
        "sampleRateHz" to sampleRateHz,
        "channels" to channels,
    )
}

/** Result of opening a host output stream, including active backend metadata and measured latency. */
data class OpenedAudioOutput(
    val line: AudioOutputLine,
    val backendName: String,
    val deviceId: String,
    val deviceName: String,
    val actualLatencyMs: Int,
)

/** Minimal abstraction over a host stereo 48 kHz 16-bit PCM capture stream. */
interface AudioInputLine : AutoCloseable {
    /**
     * Reads up to [maxFrames] stereo 16-bit interleaved PCM frames into [pcmStereoInterleaved]
     * (length >= `maxFrames * 2`), waiting up to [timeoutMs]. Returns frames read (`>= 0`) or `< 0` on error.
     */
    fun readFrames(pcmStereoInterleaved: ShortArray, maxFrames: Int, timeoutMs: Int = 50): Int
    fun latencyMs(): Int = 0
}

/** Result of opening a host input/loopback stream. */
data class OpenedAudioInput(
    val line: AudioInputLine,
    val backendMode: String,
    val deviceId: String,
    val deviceName: String,
    val actualLatencyMs: Int,
)

/** Injectable factory for deterministic capture testing. */
fun interface AudioInputLineProvider {
    fun open(
        mode: AudioInputMode,
        deviceId: String,
        sampleRateHz: Int,
        channels: Int,
        bufferMs: Int,
    ): OpenedAudioInput
}

/**
 * Abstraction over the optional M10 native low-latency FFM audio bridge (`libfuji_audio.so` / `fuji_audio.dll`).
 */
interface NativeAudioBackend {
    val available: Boolean
    val backendName: String
    fun enumerate(direction: AudioDeviceDirection): List<AudioDeviceDescriptor>
    fun openOutput(deviceId: String, sampleRateHz: Int, channels: Int, targetLatencyMs: Int): OpenedAudioOutput?
    fun openInput(
        deviceId: String,
        loopback: Boolean,
        sampleRateHz: Int,
        channels: Int,
        targetLatencyMs: Int,
    ): OpenedAudioInput?
}

/** No-op native backend used when no native library is present or in pure-JavaSound unit tests. */
object UnavailableNativeAudioBackend : NativeAudioBackend {
    override val available: Boolean = false
    override val backendName: String = "UNAVAILABLE"
    override fun enumerate(direction: AudioDeviceDirection): List<AudioDeviceDescriptor> = emptyList()
    override fun openOutput(deviceId: String, sampleRateHz: Int, channels: Int, targetLatencyMs: Int): OpenedAudioOutput? = null
    override fun openInput(
        deviceId: String,
        loopback: Boolean,
        sampleRateHz: Int,
        channels: Int,
        targetLatencyMs: Int,
    ): OpenedAudioInput? = null
}

/**
 * Direct JDK 25 FFM loader for the pinned `fuji_audio.h` (`FUJI_AUDIO_ABI_VERSION = 1`) shared library.
 * Degrades cleanly to `available = false` if the native library is absent or native access is disabled.
 */
internal class FfmNativeAudioBackend private constructor(
    override val backendName: String,
    private val arena: Arena,
    private val fnEnumerate: MethodHandle,
    private val fnOpenOutput: MethodHandle,
    private val fnOpenInput: MethodHandle,
    private val fnWrite: MethodHandle,
    private val fnRead: MethodHandle,
    private val fnGetLatencyMs: MethodHandle,
    private val fnClose: MethodHandle,
) : NativeAudioBackend {
    override val available: Boolean = true

    override fun enumerate(direction: AudioDeviceDirection): List<AudioDeviceDescriptor> {
        return runCatching {
            Arena.ofConfined().use { callArena ->
                val maxCount = 64
                val arrayBuf = callArena.allocate(DEVICE_INFO_STRIDE * maxCount.toLong(), 4L)
                val count = fnEnumerate.invokeExact(direction.nativeCode, arrayBuf, maxCount) as Int
                if (count <= 0) return emptyList()
                val actual = count.coerceAtMost(maxCount)
                val out = ArrayList<AudioDeviceDescriptor>(actual)
                for (i in 0 until actual) {
                    val base = i.toLong() * DEVICE_INFO_STRIDE
                    val id = readCString(arrayBuf, base, 256)
                    val name = readCString(arrayBuf, base + 256L, 256).ifBlank { id }
                    val isDefault = arrayBuf.get(ValueLayout.JAVA_INT_UNALIGNED, base + 512L) != 0
                    val sampleRate = arrayBuf.get(ValueLayout.JAVA_INT_UNALIGNED, base + 516L)
                        .takeIf { it > 0 } ?: HostAudioPlayback.SAMPLE_RATE_HZ
                    val channels = arrayBuf.get(ValueLayout.JAVA_INT_UNALIGNED, base + 520L)
                        .takeIf { it > 0 } ?: HostAudioPlayback.CHANNELS
                    if (id.isNotBlank()) {
                        out += AudioDeviceDescriptor(
                            id = id,
                            name = name,
                            isDefault = isDefault,
                            backend = backendName,
                            sampleRateHz = sampleRate,
                            channels = channels,
                        )
                    }
                }
                out
            }
        }.getOrElse { emptyList() }
    }

    override fun openOutput(
        deviceId: String,
        sampleRateHz: Int,
        channels: Int,
        targetLatencyMs: Int,
    ): OpenedAudioOutput? {
        val handle = Arena.ofConfined().use { callArena ->
            val idSeg = if (deviceId.isBlank()) MemorySegment.NULL else callArena.allocateFrom(deviceId, StandardCharsets.UTF_8)
            fnOpenOutput.invokeExact(idSeg, sampleRateHz, channels, targetLatencyMs) as MemorySegment
        }
        if (handle == MemorySegment.NULL) return null
        val latency = runCatching { (fnGetLatencyMs.invokeExact(handle) as Int).coerceAtLeast(1) }.getOrDefault(targetLatencyMs)
        val line = object : AudioOutputLine {
            private val ioArena = Arena.ofShared()
            private var scratch = ioArena.allocate(HostAudioPlayback.CHUNK_FRAMES.toLong() * channels * 2L, 2L)
            @Volatile private var closed = false

            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                if (closed || length <= 0) return 0
                val frames = length / (channels * 2)
                if (frames <= 0) return 0
                val byteLen = frames * channels * 2
                if (scratch.byteSize() < byteLen) {
                    scratch = ioArena.allocate(byteLen.toLong(), 2L)
                }
                MemorySegment.copy(bytes, offset, scratch, ValueLayout.JAVA_BYTE, 0L, byteLen)
                val writtenFrames = fnWrite.invokeExact(handle, scratch, frames) as Int
                if (writtenFrames < 0) {
                    throw IOException("Native audio write failed (code=$writtenFrames)")
                }
                return writtenFrames * channels * 2
            }

            override fun close() {
                if (closed) return
                closed = true
                try {
                    fnClose.invokeExact(handle)
                } catch (_: Throwable) {
                }
                runCatching { ioArena.close() }
            }
        }
        val resolvedName = enumerate(AudioDeviceDirection.OUTPUT)
            .firstOrNull { if (deviceId.isBlank()) it.isDefault else it.id == deviceId }?.name
            ?: deviceId.ifBlank { "System Default ($backendName)" }
        return OpenedAudioOutput(
            line = line,
            backendName = backendName,
            deviceId = deviceId,
            deviceName = resolvedName,
            actualLatencyMs = latency,
        )
    }

    override fun openInput(
        deviceId: String,
        loopback: Boolean,
        sampleRateHz: Int,
        channels: Int,
        targetLatencyMs: Int,
    ): OpenedAudioInput? {
        val handle = Arena.ofConfined().use { callArena ->
            val idSeg = if (deviceId.isBlank()) MemorySegment.NULL else callArena.allocateFrom(deviceId, StandardCharsets.UTF_8)
            fnOpenInput.invokeExact(idSeg, if (loopback) 1 else 0, sampleRateHz, channels, targetLatencyMs) as MemorySegment
        }
        if (handle == MemorySegment.NULL) return null
        val latency = runCatching { (fnGetLatencyMs.invokeExact(handle) as Int).coerceAtLeast(1) }.getOrDefault(targetLatencyMs)
        val line = object : AudioInputLine {
            private val ioArena = Arena.ofShared()
            private var scratch = ioArena.allocate(HostAudioPlayback.CHUNK_FRAMES.toLong() * channels * 2L, 2L)
            @Volatile private var closed = false

            override fun readFrames(pcmStereoInterleaved: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
                if (closed || maxFrames <= 0) return -1
                val neededBytes = maxFrames.toLong() * channels * 2L
                if (scratch.byteSize() < neededBytes) {
                    scratch = ioArena.allocate(neededBytes, 2L)
                }
                val readFrames = fnRead.invokeExact(handle, scratch, maxFrames, timeoutMs) as Int
                if (readFrames > 0) {
                    MemorySegment.copy(scratch, ValueLayout.JAVA_SHORT_UNALIGNED, 0L, pcmStereoInterleaved, 0, readFrames * channels)
                }
                return readFrames
            }

            override fun latencyMs(): Int = latency

            override fun close() {
                if (closed) return
                closed = true
                try {
                    fnClose.invokeExact(handle)
                } catch (_: Throwable) {
                }
                runCatching { ioArena.close() }
            }
        }
        val dir = if (loopback) AudioDeviceDirection.LOOPBACK else AudioDeviceDirection.INPUT
        val resolvedName = enumerate(dir)
            .firstOrNull { if (deviceId.isBlank()) it.isDefault else it.id == deviceId }?.name
            ?: deviceId.ifBlank { if (loopback) "System Default Output Loopback ($backendName)" else "System Default Input ($backendName)" }
        val modeTag = if (loopback) "NATIVE_${backendName}_LOOPBACK" else "NATIVE_$backendName"
        return OpenedAudioInput(
            line = line,
            backendMode = modeTag,
            deviceId = deviceId,
            deviceName = resolvedName,
            actualLatencyMs = latency,
        )
    }

    companion object {
        private const val EXPECTED_ABI_VERSION = 1
        private const val DEVICE_INFO_STRIDE = 524L
        private val log = LoggerFactory.getLogger(FfmNativeAudioBackend::class.java)

        private fun normalizeBackendName(raw: String): String = when (raw.trim().lowercase()) {
            "wasapi" -> "WASAPI"
            "pulse-pipewire", "pulseaudio", "pipewire" -> "PULSE_PIPEWIRE"
            else -> raw.trim().uppercase().ifBlank { "NATIVE" }
        }

        private fun readCString(segment: MemorySegment, offset: Long, maxLen: Int): String {
            val bytes = ByteArray(maxLen)
            var len = 0
            while (len < maxLen) {
                val b = segment.get(ValueLayout.JAVA_BYTE, offset + len)
                if (b.toInt() == 0) break
                bytes[len++] = b
            }
            return String(bytes, 0, len, StandardCharsets.UTF_8)
        }

        private fun candidatePaths(home: Path? = null): List<Path> {
            val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
            val libName = if (isWindows) "fuji_audio.dll" else "libfuji_audio.so"
            val paths = ArrayList<Path>()
            System.getProperty("fuji.audio.library")?.takeIf { it.isNotBlank() }?.let { paths.add(Path.of(it)) }
            System.getenv("FUJI_AUDIO_LIBRARY")?.takeIf { it.isNotBlank() }?.let { paths.add(Path.of(it)) }
            val roots = buildList {
                if (home != null) add(home.toAbsolutePath().normalize())
                add(Path.of("").toAbsolutePath().normalize())
            }.distinct()
            val relCandidates = listOf(
                "runtime/native/$libName",
                "lib/$libName",
                libName,
                ".tools/audio-bridge/build-linux/$libName",
                ".tools/audio-bridge/build-win64/$libName",
                "build/audio-bridge/$libName",
            )
            for (root in roots) {
                for (rel in relCandidates) {
                    paths.add(root.resolve(rel))
                }
            }
            return paths.distinct()
        }

        fun tryLoad(home: Path? = null): NativeAudioBackend {
            val existing = candidatePaths(home).firstOrNull { Files.isRegularFile(it) } ?: return UnavailableNativeAudioBackend
            val arena = Arena.ofAuto()
            return try {
                val lookup = SymbolLookup.libraryLookup(existing, arena)
                val linker = Linker.nativeLinker()
                fun bind(name: String, desc: FunctionDescriptor): MethodHandle {
                    val sym = lookup.find(name).orElseThrow { UnsatisfiedLinkError("Missing symbol $name in $existing") }
                    return linker.downcallHandle(sym, desc)
                }
                val fnAbi = bind("fuji_audio_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT))
                val abi = fnAbi.invokeExact() as Int
                if (abi != EXPECTED_ABI_VERSION) {
                    log.warn("Ignoring native audio library {} with unsupported ABI {} (expected {})", existing, abi, EXPECTED_ABI_VERSION)
                    return UnavailableNativeAudioBackend
                }
                val fnBackendName = bind("fuji_audio_backend_name", FunctionDescriptor.of(ValueLayout.ADDRESS))
                val namePtr = fnBackendName.invokeExact() as MemorySegment
                val rawName = if (namePtr != MemorySegment.NULL) {
                    namePtr.reinterpret(128L).getString(0L, StandardCharsets.UTF_8)
                } else {
                    "NATIVE"
                }
                val fnEnum = bind(
                    "fuji_audio_enumerate",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                )
                val fnOpenOut = bind(
                    "fuji_audio_open_output",
                    FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                    ),
                )
                val fnOpenIn = bind(
                    "fuji_audio_open_input",
                    FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                    ),
                )
                val fnWrite = bind(
                    "fuji_audio_write",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                )
                val fnRead = bind(
                    "fuji_audio_read",
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                    ),
                )
                val fnLatency = bind(
                    "fuji_audio_get_latency_ms",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                )
                val fnClose = bind(
                    "fuji_audio_close",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                )
                val backendName = normalizeBackendName(rawName)
                log.info("Loaded native low-latency audio bridge {} (backend={})", existing, backendName)
                FfmNativeAudioBackend(
                    backendName = backendName,
                    arena = arena,
                    fnEnumerate = fnEnum,
                    fnOpenOutput = fnOpenOut,
                    fnOpenInput = fnOpenIn,
                    fnWrite = fnWrite,
                    fnRead = fnRead,
                    fnGetLatencyMs = fnLatency,
                    fnClose = fnClose,
                )
            } catch (err: Throwable) {
                log.warn("[FALLBACK][AUDIO] Native low-latency audio bridge unavailable ({}): {}; falling back to javax.sound / headless", existing, err.message ?: err.javaClass.simpleName)
                UnavailableNativeAudioBackend
            }
        }
    }
}

/**
 * Discovers and opens host audio output, microphone input, and system-output loopback devices across
 * the native FFM bridge (`WASAPI` / `PULSE_PIPEWIRE`), tuned `javax.sound.sampled` mixers, and optional
 * Linux `parec` monitor fallback.
 */
class AudioDeviceCatalog(
    private val nativeBackend: NativeAudioBackend = FfmNativeAudioBackend.tryLoad(),
    private val javaSoundOutputsProvider: () -> List<AudioDeviceDescriptor> = ::scanJavaSoundOutputs,
    private val javaSoundInputsProvider: () -> Pair<List<AudioDeviceDescriptor>, List<AudioDeviceDescriptor>> = ::scanJavaSoundInputsAndLoopbacks,
    private val javaSoundOutputOpener: (deviceId: String, format: AudioFormat, bufferBytes: Int) -> OpenedAudioOutput = ::openJavaSoundOutput,
    private val javaSoundInputOpener: (deviceId: String, loopback: Boolean, format: AudioFormat, bufferBytes: Int) -> OpenedAudioInput = ::openJavaSoundInput,
    private val parecLoopbackOpener: ((deviceId: String, sampleRateHz: Int, channels: Int, bufferMs: Int) -> OpenedAudioInput?)? = ::tryOpenLinuxParecLoopback,
) {
    val nativeAvailable: Boolean get() = nativeBackend.available
    val nativeBackendName: String get() = if (nativeBackend.available) nativeBackend.backendName else "NONE"

    fun listDevices(): Map<String, Any?> {
        val outputs = ArrayList<AudioDeviceDescriptor>()
        val inputs = ArrayList<AudioDeviceDescriptor>()
        val loopbacks = ArrayList<AudioDeviceDescriptor>()

        if (nativeBackend.available) {
            outputs += nativeBackend.enumerate(AudioDeviceDirection.OUTPUT)
            inputs += nativeBackend.enumerate(AudioDeviceDirection.INPUT)
            loopbacks += nativeBackend.enumerate(AudioDeviceDirection.LOOPBACK)
        }

        val jsOutputs = runCatching { javaSoundOutputsProvider() }.getOrDefault(emptyList())
        val (jsInputs, jsLoopbacks) = runCatching { javaSoundInputsProvider() }.getOrDefault(emptyList<AudioDeviceDescriptor>() to emptyList())

        val hasNativeOutDefault = outputs.any { it.isDefault }
        for (d in jsOutputs) {
            outputs += if (hasNativeOutDefault) d.copy(isDefault = false) else d
        }
        val hasNativeInDefault = inputs.any { it.isDefault }
        for (d in jsInputs) {
            inputs += if (hasNativeInDefault) d.copy(isDefault = false) else d
        }
        val hasNativeLoopDefault = loopbacks.any { it.isDefault }
        for (d in jsLoopbacks) {
            loopbacks += if (hasNativeLoopDefault) d.copy(isDefault = false) else d
        }

        return linkedMapOf(
            "outputs" to outputs.map { it.toMap() },
            "inputs" to inputs.map { it.toMap() },
            "loopbacks" to loopbacks.map { it.toMap() },
            "nativeAvailable" to nativeBackend.available,
            "nativeBackend" to nativeBackendName,
        )
    }

    fun openOutput(
        preference: AudioBackendPreference,
        deviceId: String,
        format: AudioFormat,
        bufferMs: Int,
        bufferBytes: Int,
    ): OpenedAudioOutput {
        val forceJavaSound = preference == AudioBackendPreference.JAVASOUND || deviceId.startsWith(JAVASOUND_PREFIX)
        if (!forceJavaSound && nativeBackend.available) {
            val nativeId = deviceId.removePrefix(NATIVE_PREFIX)
            val opened = runCatching {
                nativeBackend.openOutput(nativeId, format.sampleRate.toInt(), format.channels, bufferMs)
            }.getOrNull()
            if (opened != null) return opened
            if (preference == AudioBackendPreference.NATIVE) {
                throw LineUnavailableException("Native audio output unavailable (${nativeBackend.backendName}) for device '$deviceId'")
            }
        } else if (preference == AudioBackendPreference.NATIVE) {
            throw LineUnavailableException("Native audio bridge not loaded (requested backendPreference=NATIVE)")
        }
        return javaSoundOutputOpener(deviceId, format, bufferBytes)
    }

    fun openInput(
        preference: AudioBackendPreference,
        mode: AudioInputMode,
        deviceId: String,
        format: AudioFormat,
        bufferMs: Int,
        bufferBytes: Int,
    ): OpenedAudioInput {
        val loopback = mode == AudioInputMode.OUTPUT_LOOPBACK
        val forceJavaSound = preference == AudioBackendPreference.JAVASOUND || deviceId.startsWith(JAVASOUND_PREFIX)

        if (!forceJavaSound && nativeBackend.available) {
            val nativeId = deviceId.removePrefix(NATIVE_PREFIX)
            val opened = runCatching {
                nativeBackend.openInput(nativeId, loopback, format.sampleRate.toInt(), format.channels, bufferMs)
            }.getOrNull()
            if (opened != null) return opened
            if (preference == AudioBackendPreference.NATIVE) {
                throw LineUnavailableException(
                    "Native audio ${if (loopback) "loopback" else "input"} unavailable (${nativeBackend.backendName}) for device '$deviceId'",
                )
            }
        } else if (preference == AudioBackendPreference.NATIVE) {
            throw LineUnavailableException("Native audio bridge not loaded (requested backendPreference=NATIVE)")
        }

        val jsErr = try {
            return javaSoundInputOpener(deviceId, loopback, format, bufferBytes)
        } catch (err: Throwable) {
            err
        }

        val parecOpener = parecLoopbackOpener
        if (loopback && parecOpener != null) {
            val parec = runCatching {
                parecOpener.invoke(deviceId, format.sampleRate.toInt(), format.channels, bufferMs)
            }.getOrNull()
            if (parec != null) return parec
        }

        throw jsErr
    }

    companion object {
        const val JAVASOUND_PREFIX = "js:"
        const val NATIVE_PREFIX = "native:"

        private val LOOPBACK_KEYWORDS = listOf(
            "monitor of",
            ".monitor",
            "stereo mix",
            "立体声混音",
            "what u hear",
            "wave out mix",
            "cable output",
            "vb-audio",
            "blackhole",
            "soundflower",
            "loopback",
        )

        fun defaultCatalog(home: Path? = null): AudioDeviceCatalog = AudioDeviceCatalog(
            nativeBackend = FfmNativeAudioBackend.tryLoad(home),
        )

        internal fun isLoopbackMixerName(name: String, description: String = ""): Boolean {
            val combined = "$name $description".lowercase()
            return LOOPBACK_KEYWORDS.any { it in combined }
        }

        internal fun scanJavaSoundOutputs(): List<AudioDeviceDescriptor> {
            val format = HostAudioPlayback.AUDIO_FORMAT_48K_16BIT_STEREO
            val info = DataLine.Info(SourceDataLine::class.java, format)
            val result = ArrayList<AudioDeviceDescriptor>()
            for (mixerInfo in AudioSystem.getMixerInfo()) {
                val supported = runCatching { AudioSystem.getMixer(mixerInfo).isLineSupported(info) }.getOrDefault(false)
                if (!supported) continue
                result += AudioDeviceDescriptor(
                    id = "${JAVASOUND_PREFIX}out:${mixerInfo.name}",
                    name = mixerInfo.name,
                    isDefault = result.isEmpty(),
                    backend = "JAVASOUND",
                )
            }
            return result
        }

        internal fun scanJavaSoundInputsAndLoopbacks(): Pair<List<AudioDeviceDescriptor>, List<AudioDeviceDescriptor>> {
            val format = HostAudioPlayback.AUDIO_FORMAT_48K_16BIT_STEREO
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val mics = ArrayList<AudioDeviceDescriptor>()
            val loopbacks = ArrayList<AudioDeviceDescriptor>()
            for (mixerInfo in AudioSystem.getMixerInfo()) {
                val supported = runCatching { AudioSystem.getMixer(mixerInfo).isLineSupported(info) }.getOrDefault(false)
                if (!supported) continue
                if (isLoopbackMixerName(mixerInfo.name, mixerInfo.description)) {
                    loopbacks += AudioDeviceDescriptor(
                        id = "${JAVASOUND_PREFIX}loop:${mixerInfo.name}",
                        name = mixerInfo.name,
                        isDefault = loopbacks.isEmpty(),
                        backend = "JAVASOUND",
                    )
                } else {
                    mics += AudioDeviceDescriptor(
                        id = "${JAVASOUND_PREFIX}in:${mixerInfo.name}",
                        name = mixerInfo.name,
                        isDefault = mics.isEmpty(),
                        backend = "JAVASOUND",
                    )
                }
            }
            return mics to loopbacks
        }

        private fun findMixerByName(rawId: String, prefix: String): Mixer.Info? {
            if (rawId.isBlank()) return null
            val targetName = rawId.removePrefix(prefix).removePrefix(JAVASOUND_PREFIX)
            return AudioSystem.getMixerInfo().firstOrNull {
                it.name == targetName || it.name.equals(targetName, ignoreCase = true)
            }
        }

        internal fun openJavaSoundOutput(deviceId: String, format: AudioFormat, bufferBytes: Int): OpenedAudioOutput {
            val info = DataLine.Info(SourceDataLine::class.java, format)
            val mixerInfo = findMixerByName(deviceId, "${JAVASOUND_PREFIX}out:")
            val line: SourceDataLine = if (mixerInfo != null) {
                AudioSystem.getMixer(mixerInfo).getLine(info) as SourceDataLine
            } else {
                AudioSystem.getLine(info) as SourceDataLine
            }
            line.open(format, bufferBytes)
            line.start()
            val actualLatencyMs = ((line.bufferSize.toDouble() / (format.frameSize * format.sampleRate)) * 1000.0)
                .toInt().coerceAtLeast(1)
            val wrapper = object : AudioOutputLine {
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
                    line.write(bytes, offset, length)

                override fun flush() {
                    runCatching { line.flush() }
                }

                override fun close() {
                    runCatching { line.stop() }
                    runCatching { line.flush() }
                    runCatching { line.close() }
                }
            }
            return OpenedAudioOutput(
                line = wrapper,
                backendName = "JAVASOUND",
                deviceId = deviceId,
                deviceName = mixerInfo?.name ?: "System Default (JavaSound)",
                actualLatencyMs = actualLatencyMs,
            )
        }

        internal fun openJavaSoundInput(
            deviceId: String,
            loopback: Boolean,
            format: AudioFormat,
            bufferBytes: Int,
        ): OpenedAudioInput {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val prefix = if (loopback) "${JAVASOUND_PREFIX}loop:" else "${JAVASOUND_PREFIX}in:"
            val mixerInfo: Mixer.Info? = when {
                deviceId.isNotBlank() -> findMixerByName(deviceId, prefix)
                    ?: throw LineUnavailableException("Audio input mixer not found: $deviceId")
                loopback -> AudioSystem.getMixerInfo().firstOrNull { mi ->
                    isLoopbackMixerName(mi.name, mi.description) &&
                        runCatching { AudioSystem.getMixer(mi).isLineSupported(info) }.getOrDefault(false)
                } ?: throw LineUnavailableException(
                    "No JavaSound loopback mixer detected (Monitor of / Stereo Mix / CABLE Output); install native audio bridge or enable Stereo Mix",
                )
                else -> null
            }

            val line: TargetDataLine = if (mixerInfo != null) {
                AudioSystem.getMixer(mixerInfo).getLine(info) as TargetDataLine
            } else {
                AudioSystem.getLine(info) as TargetDataLine
            }
            line.open(format, bufferBytes)
            line.start()
            val actualLatencyMs = ((line.bufferSize.toDouble() / (format.frameSize * format.sampleRate)) * 1000.0)
                .toInt().coerceAtLeast(1)
            val byteScratch = ByteArray(HostAudioPlayback.CHUNK_FRAMES * HostAudioPlayback.BYTES_PER_FRAME)
            val wrapper = object : AudioInputLine {
                private var localBuf = byteScratch
                @Volatile private var closed = false

                override fun readFrames(pcmStereoInterleaved: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
                    if (closed || maxFrames <= 0) return -1
                    val wantBytes = maxFrames * HostAudioPlayback.BYTES_PER_FRAME
                    if (localBuf.size < wantBytes) {
                        localBuf = ByteArray(wantBytes)
                    }
                    val nBytes = line.read(localBuf, 0, wantBytes)
                    if (nBytes <= 0) return 0
                    val framesRead = nBytes / HostAudioPlayback.BYTES_PER_FRAME
                    var src = 0
                    var dst = 0
                    for (i in 0 until framesRead) {
                        val l = (localBuf[src].toInt() and 0xFF) or (localBuf[src + 1].toInt() shl 8)
                        val r = (localBuf[src + 2].toInt() and 0xFF) or (localBuf[src + 3].toInt() shl 8)
                        pcmStereoInterleaved[dst++] = l.toShort()
                        pcmStereoInterleaved[dst++] = r.toShort()
                        src += 4
                    }
                    return framesRead
                }

                override fun latencyMs(): Int = actualLatencyMs

                override fun close() {
                    if (closed) return
                    closed = true
                    runCatching { line.stop() }
                    runCatching { line.flush() }
                    runCatching { line.close() }
                }
            }
            return OpenedAudioInput(
                line = wrapper,
                backendMode = if (loopback) "JAVASOUND_LOOPBACK" else "JAVASOUND_MIC",
                deviceId = deviceId,
                deviceName = mixerInfo?.name ?: if (loopback) "JavaSound Loopback" else "System Default Mic (JavaSound)",
                actualLatencyMs = actualLatencyMs,
            )
        }

        internal fun tryOpenLinuxParecLoopback(
            deviceId: String,
            sampleRateHz: Int,
            channels: Int,
            bufferMs: Int,
        ): OpenedAudioInput? {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            if ("linux" !in os) return null
            val monitorTarget = when {
                deviceId.isBlank() -> "@DEFAULT_MONITOR@"
                deviceId.endsWith(".monitor") -> deviceId
                else -> "$deviceId.monitor"
            }
            val cmd = listOf(
                "parec",
                "--format=s16le",
                "--rate=$sampleRateHz",
                "--channels=$channels",
                "--latency-msec=${bufferMs.coerceIn(10, 200)}",
                "--device=$monitorTarget",
            )
            val process = try {
                ProcessBuilder(cmd).redirectErrorStream(false).start()
            } catch (_: Throwable) {
                return null
            }
            // Brief liveness probe so immediate "No such entity" / missing Pulse daemon degrades cleanly.
            Thread.sleep(25L)
            if (!process.isAlive) {
                runCatching { process.destroyForcibly() }
                return null
            }
            val input: InputStream = process.inputStream
            val wrapper = object : AudioInputLine {
                private var raw = ByteArray(HostAudioPlayback.CHUNK_FRAMES * channels * 2)
                @Volatile private var closed = false

                override fun readFrames(pcmStereoInterleaved: ShortArray, maxFrames: Int, timeoutMs: Int): Int {
                    if (closed || !process.isAlive) return -1
                    val wantBytes = maxFrames * channels * 2
                    if (raw.size < wantBytes) raw = ByteArray(wantBytes)
                    var readTotal = 0
                    while (readTotal < wantBytes) {
                        val n = input.read(raw, readTotal, wantBytes - readTotal)
                        if (n < 0) return if (readTotal == 0) -1 else readTotal / (channels * 2)
                        readTotal += n
                    }
                    val frames = readTotal / (channels * 2)
                    var src = 0
                    var dst = 0
                    for (i in 0 until frames * channels) {
                        val s = (raw[src].toInt() and 0xFF) or (raw[src + 1].toInt() shl 8)
                        pcmStereoInterleaved[dst++] = s.toShort()
                        src += 2
                    }
                    return frames
                }

                override fun latencyMs(): Int = bufferMs

                override fun close() {
                    if (closed) return
                    closed = true
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                    runCatching { process.outputStream.close() }
                    if (process.isAlive) process.destroyForcibly()
                }
            }
            return OpenedAudioInput(
                line = wrapper,
                backendMode = "PAREC_LOOPBACK",
                deviceId = deviceId,
                deviceName = "Pulse/PipeWire Monitor ($monitorTarget)",
                actualLatencyMs = bufferMs,
            )
        }
    }
}
