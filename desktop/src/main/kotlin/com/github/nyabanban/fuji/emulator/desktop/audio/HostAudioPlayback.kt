package com.github.nyabanban.fuji.emulator.desktop.audio

import com.github.nyabanban.fuji.emulator.api.AudioCodecControlPort
import com.github.nyabanban.fuji.emulator.api.AudioOutputRoute
import com.github.nyabanban.fuji.emulator.api.AudioSinkKind
import com.github.nyabanban.fuji.emulator.api.GpioInputPort
import com.github.nyabanban.fuji.emulator.api.I2sAudioPort
import com.github.nyabanban.fuji.emulator.api.Ports
import com.github.nyabanban.fuji.emulator.api.SceneAudioProvider
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.runtime.NativeOsScheduler
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.roundToInt

/** Runtime configuration for opt-in JVM / native full-duplex host audio playback & capture. */
data class AudioPlaybackConfig(
    val enabled: Boolean = false,
    val volume: Double = DEFAULT_VOLUME,
    val muted: Boolean = false,
    val bufferMs: Int = DEFAULT_BUFFER_MS,
    val outputDeviceId: String = "",
    val backendPreference: AudioBackendPreference = AudioBackendPreference.AUTO,
    val inputMode: AudioInputMode = AudioInputMode.DISABLED,
    val inputDeviceId: String = "",
    val inputGainDb: Double = 0.0,
    val inputMuted: Boolean = false,
    val inputMonitor: Boolean = false,
    val testToneHz: Double = HostAudioCapture.DEFAULT_TEST_TONE_HZ,
) {
    init {
        require(volume.isFinite() && volume in 0.0..1.0) { "audio.volume must be within 0.0..1.0, got $volume" }
        require(bufferMs in MIN_BUFFER_MS..MAX_BUFFER_MS) {
            "audio.bufferMs must be within $MIN_BUFFER_MS..$MAX_BUFFER_MS, got $bufferMs"
        }
        require(inputGainDb.isFinite() && inputGainDb in HostAudioCapture.MIN_GAIN_DB..HostAudioCapture.MAX_GAIN_DB) {
            "audio.inputGainDb must be within ${HostAudioCapture.MIN_GAIN_DB}..${HostAudioCapture.MAX_GAIN_DB}, got $inputGainDb"
        }
        require(testToneHz.isFinite() && testToneHz in HostAudioCapture.MIN_TEST_TONE_HZ..HostAudioCapture.MAX_TEST_TONE_HZ) {
            "audio.testToneHz must be within ${HostAudioCapture.MIN_TEST_TONE_HZ}..${HostAudioCapture.MAX_TEST_TONE_HZ}, got $testToneHz"
        }
    }

    companion object {
        const val CONFIG_KEY = "audio"
        const val DEFAULT_VOLUME = 0.8
        const val DEFAULT_BUFFER_MS = 60
        const val MIN_BUFFER_MS = 10
        const val MAX_BUFFER_MS = 500
        private val VALID_MAP_KEYS = setOf(
            "enabled",
            "volume",
            "muted",
            "bufferMs",
            "outputDeviceId",
            "backendPreference",
            "inputMode",
            "inputDeviceId",
            "inputGainDb",
            "inputMuted",
            "inputMonitor",
            "testToneHz",
        )

        fun validateConfigValue(raw: Any?) {
            if (raw == null) return
            when (raw) {
                is Boolean -> Unit
                is Map<*, *> -> {
                    require(raw.keys.all { it is String && it in VALID_MAP_KEYS }) { "invalid audio config keys" }
                    raw["enabled"]?.let { require(it is Boolean) { "audio.enabled requires boolean" } }
                    raw["muted"]?.let { require(it is Boolean) { "audio.muted requires boolean" } }
                    raw["volume"]?.let { v ->
                        require(v is Number) { "audio.volume requires number" }
                        val d = v.toDouble()
                        require(d.isFinite() && d in 0.0..1.0) { "audio.volume must be within 0.0..1.0" }
                    }
                    raw["bufferMs"]?.let { v ->
                        require(v is Byte || v is Short || v is Int || v is Long) { "audio.bufferMs requires integer" }
                        val ms = (v as Number).toInt()
                        require(ms in MIN_BUFFER_MS..MAX_BUFFER_MS) {
                            "audio.bufferMs must be within $MIN_BUFFER_MS..$MAX_BUFFER_MS"
                        }
                    }
                    raw["outputDeviceId"]?.let { require(it is String) { "audio.outputDeviceId requires string" } }
                    raw["backendPreference"]?.let { v ->
                        require(v is String) { "audio.backendPreference requires string" }
                        AudioBackendPreference.parse(v)
                    }
                    raw["inputMode"]?.let { v ->
                        require(v is String) { "audio.inputMode requires string" }
                        AudioInputMode.parse(v)
                    }
                    raw["inputDeviceId"]?.let { require(it is String) { "audio.inputDeviceId requires string" } }
                    raw["inputGainDb"]?.let { v ->
                        require(v is Number) { "audio.inputGainDb requires number" }
                        val d = v.toDouble()
                        require(d.isFinite() && d in HostAudioCapture.MIN_GAIN_DB..HostAudioCapture.MAX_GAIN_DB) {
                            "audio.inputGainDb must be within ${HostAudioCapture.MIN_GAIN_DB}..${HostAudioCapture.MAX_GAIN_DB}"
                        }
                    }
                    raw["inputMuted"]?.let { require(it is Boolean) { "audio.inputMuted requires boolean" } }
                    raw["inputMonitor"]?.let { require(it is Boolean) { "audio.inputMonitor requires boolean" } }
                    raw["testToneHz"]?.let { v ->
                        require(v is Number) { "audio.testToneHz requires number" }
                        val d = v.toDouble()
                        require(d.isFinite() && d in HostAudioCapture.MIN_TEST_TONE_HZ..HostAudioCapture.MAX_TEST_TONE_HZ) {
                            "audio.testToneHz must be within ${HostAudioCapture.MIN_TEST_TONE_HZ}..${HostAudioCapture.MAX_TEST_TONE_HZ}"
                        }
                    }
                }
                else -> throw IllegalArgumentException("audio config requires boolean or map")
            }
        }

        fun resolve(
            config: Map<String, Any?>,
            cliEnabled: Boolean = false,
            cliVolume: Double? = null,
        ): AudioPlaybackConfig {
            val raw = config[CONFIG_KEY]
            validateConfigValue(raw)
            var enabled = false
            var volume = DEFAULT_VOLUME
            var muted = false
            var bufferMs = DEFAULT_BUFFER_MS
            var outputDeviceId = ""
            var backendPreference = AudioBackendPreference.AUTO
            var inputMode = AudioInputMode.DISABLED
            var inputDeviceId = ""
            var inputGainDb = 0.0
            var inputMuted = false
            var inputMonitor = false
            var testToneHz = HostAudioCapture.DEFAULT_TEST_TONE_HZ
            when (raw) {
                is Boolean -> enabled = raw
                is Map<*, *> -> {
                    if (raw["enabled"] is Boolean) enabled = raw["enabled"] as Boolean
                    if (raw["muted"] is Boolean) muted = raw["muted"] as Boolean
                    (raw["volume"] as? Number)?.let { volume = it.toDouble() }
                    (raw["bufferMs"] as? Number)?.let { bufferMs = it.toInt() }
                    (raw["outputDeviceId"] as? String)?.let { outputDeviceId = it }
                    (raw["backendPreference"] as? String)?.let { backendPreference = AudioBackendPreference.parse(it) }
                    (raw["inputMode"] as? String)?.let { inputMode = AudioInputMode.parse(it) }
                    (raw["inputDeviceId"] as? String)?.let { inputDeviceId = it }
                    (raw["inputGainDb"] as? Number)?.let { inputGainDb = it.toDouble() }
                    if (raw["inputMuted"] is Boolean) inputMuted = raw["inputMuted"] as Boolean
                    if (raw["inputMonitor"] is Boolean) inputMonitor = raw["inputMonitor"] as Boolean
                    (raw["testToneHz"] as? Number)?.let { testToneHz = it.toDouble() }
                }
            }
            if (cliEnabled) enabled = true
            if (cliVolume != null) volume = cliVolume
            return AudioPlaybackConfig(
                enabled = enabled,
                volume = volume,
                muted = muted,
                bufferMs = bufferMs,
                outputDeviceId = outputDeviceId,
                backendPreference = backendPreference,
                inputMode = inputMode,
                inputDeviceId = inputDeviceId,
                inputGainDb = inputGainDb,
                inputMuted = inputMuted,
                inputMonitor = inputMonitor,
                testToneHz = testToneHz,
            )
        }
    }
}

/** Minimal abstraction over `javax.sound.sampled.SourceDataLine` for deterministic testing and headless fallback. */
interface AudioOutputLine : AutoCloseable {
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
    fun flush() {}
}

fun interface AudioLineProvider {
    fun open(format: AudioFormat, bufferBytes: Int): AudioOutputLine
}

object JvmAudioLineProvider : AudioLineProvider {
    override fun open(format: AudioFormat, bufferBytes: Int): AudioOutputLine {
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = AudioSystem.getLine(info) as SourceDataLine
        line.open(format, bufferBytes)
        line.start()
        return object : AudioOutputLine {
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
    }
}

/** Active backend mode of [HostAudioPlayback]. */
enum class AudioBackendMode {
    DISABLED,
    ACTIVE_LINE,
    HEADLESS_SILENT,
}

/**
 * Full-duplex 48 kHz 16-bit stereo host audio playback + microphone/loopback capture engine.
 *
 * - Runs playback (`fuji-audio-playback`) and capture (`fuji-audio-capture`) on non-blocking daemon
 *   threads so the ARM emulation owner thread never stalls.
 * - Routes automatically across native FFM (`WASAPI` / `PULSE_PIPEWIRE`), tuned low-latency
 *   `javax.sound.sampled` per-mixer lines, and `HEADLESS_SILENT` fallback.
 * - Supports hot device switching, output loopback capture (`OUTPUT_LOOPBACK`), real-time gain with
 *   soft limiter, live L/R VU peak meters, and unified [applyPatch] / [listDevices] APIs.
 */
class HostAudioPlayback @JvmOverloads constructor(
    private val portsProvider: () -> Ports?,
    initialConfig: AudioPlaybackConfig = AudioPlaybackConfig(),
    private val lineProvider: AudioLineProvider = JvmAudioLineProvider,
    private val onRenderedPcm: ((ByteArray, Int) -> Unit)? = null,
    private val deviceCatalog: AudioDeviceCatalog = AudioDeviceCatalog.defaultCatalog(),
    inputLineProvider: AudioInputLineProvider? = null,
    rxSink: AudioRxSink? = null,
) : AutoCloseable {
    companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = 4
        const val CHUNK_FRAMES = 512

        // GFX100S 2.13 3.5mm jack GPIO pins (section4.bin @ 0x01812DA4 / 0x01812DC4, active-low: plugged = 0)
        const val EXT_MIC_GPIO_PORT = 6
        const val EXT_MIC_GPIO_PIN = 4
        const val HEADPHONE_GPIO_PORT = 8
        const val HEADPHONE_GPIO_PIN = 4

        val AUDIO_FORMAT_48K_16BIT_STEREO = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            SAMPLE_RATE_HZ.toFloat(),
            16,
            CHANNELS,
            BYTES_PER_FRAME,
            SAMPLE_RATE_HZ.toFloat(),
            false, // little-endian
        )

        val PATCH_KEYS: Set<String> = setOf(
            "enabled",
            "muted",
            "volume",
            "outputDeviceId",
            "bufferMs",
            "backendPreference",
            "headphonePlugged",
            "inputMode",
            "inputDeviceId",
            "inputGainDb",
            "inputMuted",
            "inputMonitor",
            "testToneHz",
            "extMicPlugged",
        )

        /** Apply WM8962 route (when present) and host master volume/mute to interleaved 16-bit stereo samples. */
        internal fun renderToLittleEndianBytes(
            samples: ShortArray,
            frames: Int,
            route: AudioOutputRoute?,
            masterVolume: Double,
            masterMuted: Boolean,
            target: ByteArray,
            peaksOut: DoubleArray? = null,
        ): Int {
            val byteLength = frames * BYTES_PER_FRAME
            if (masterMuted || masterVolume <= 0.0 || route?.activeSink == AudioSinkKind.MUTED) {
                target.fill(0, 0, byteLength)
                if (peaksOut != null && peaksOut.size >= 2) {
                    peaksOut[0] = 0.0
                    peaksOut[1] = 0.0
                }
                return byteLength
            }
            val monoMix = route?.activeSink == AudioSinkKind.SPEAKER_MONO
            val leftGain = (route?.effectiveLeftGain?.toDouble() ?: 1.0) * masterVolume
            val rightGain = (route?.effectiveRightGain?.toDouble() ?: 1.0) * masterVolume
            var outIdx = 0
            var peakL = 0
            var peakR = 0
            for (i in 0 until frames) {
                val rawL = samples[i * 2].toInt()
                val rawR = samples[i * 2 + 1].toInt()
                val mixedL: Int
                val mixedR: Int
                if (monoMix) {
                    val mono = ((rawL + rawR) * 0.5 * leftGain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    mixedL = mono
                    mixedR = mono
                } else {
                    mixedL = (rawL * leftGain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    mixedR = (rawR * rightGain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                }
                val aL = abs(mixedL)
                val aR = abs(mixedR)
                if (aL > peakL) peakL = aL
                if (aR > peakR) peakR = aR
                target[outIdx] = (mixedL and 0xFF).toByte()
                target[outIdx + 1] = ((mixedL ushr 8) and 0xFF).toByte()
                target[outIdx + 2] = (mixedR and 0xFF).toByte()
                target[outIdx + 3] = ((mixedR ushr 8) and 0xFF).toByte()
                outIdx += 4
            }
            if (peaksOut != null && peaksOut.size >= 2) {
                peaksOut[0] = (peakL / 32768.0).coerceIn(0.0, 1.0)
                peaksOut[1] = (peakR / 32768.0).coerceIn(0.0, 1.0)
            }
            return byteLength
        }
    }

    private val log = LoggerFactory.getLogger(HostAudioPlayback::class.java)
    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    @Volatile
    var enabled: Boolean = initialConfig.enabled
        private set

    @Volatile
    var volume: Double = initialConfig.volume
        private set

    @Volatile
    var muted: Boolean = initialConfig.muted
        private set

    @Volatile
    var bufferMs: Int = initialConfig.bufferMs
        private set

    @Volatile
    var outputDeviceId: String = initialConfig.outputDeviceId
        private set

    @Volatile
    var outputDeviceName: String = ""
        private set

    @Volatile
    var backendPreference: AudioBackendPreference = initialConfig.backendPreference
        private set

    @Volatile
    var backendMode: AudioBackendMode = AudioBackendMode.DISABLED
        private set

    @Volatile
    var activeBackend: String = "DISABLED"
        private set

    @Volatile
    var actualLatencyMs: Int = 0
        private set

    @Volatile
    var degradationReason: String? = null
        private set

    @Volatile
    var outputPeakLeft: Double = 0.0
        private set

    @Volatile
    var outputPeakRight: Double = 0.0
        private set

    @Volatile
    var headphonePlugged: Boolean = false
        private set

    @Volatile
    var extMicPlugged: Boolean = false
        private set

    private val framesPlayed = AtomicLong(0L)
    private val chunksPlayed = AtomicLong(0L)
    private val idlePolls = AtomicLong(0L)

    @Volatile
    private var boundI2s: I2sAudioPort? = null

    @Volatile
    private var boundCodec: AudioCodecControlPort? = null

    @Volatile
    private var boundGpio: GpioInputPort? = null
    @Volatile private var boundSceneAudio: SceneAudioProvider? = null

    private var workerThread: Thread? = null
    private var activeLine: AudioOutputLine? = null
    private val pumpLock = Any()
    private val renderBuffer = ByteArray(CHUNK_FRAMES * BYTES_PER_FRAME)
    private val mixShortScratch = ShortArray(CHUNK_FRAMES * 2)
    private val peakScratch = DoubleArray(2)

    // Bounded lock-free-style synchronized ring for optional input monitor passthrough into output.
    private val monitorLock = Any()
    private val monitorCapacityFrames = 4096
    private val monitorRing = ShortArray(monitorCapacityFrames * 2)
    private var monitorReadPos = 0
    private var monitorWritePos = 0
    private var monitorQueuedFrames = 0

    val capture: HostAudioCapture = HostAudioCapture(
        rxSink = rxSink ?: I2sPortRxSinkAdapter(::resolveI2sPort),
        deviceCatalog = deviceCatalog,
        inputLineProvider = inputLineProvider,
        onMonitorPcm = ::enqueueMonitorPcm,
        sceneAudioProvider = ::resolveSceneAudioPort,
        initialMode = initialConfig.inputMode,
        initialDeviceId = initialConfig.inputDeviceId,
        initialGainDb = initialConfig.inputGainDb,
        initialMuted = initialConfig.inputMuted,
        initialMonitor = initialConfig.inputMonitor,
        initialTestToneHz = initialConfig.testToneHz,
        initialBufferMs = initialConfig.bufferMs,
        initialBackendPreference = initialConfig.backendPreference,
    )

    private fun enqueueMonitorPcm(pcmStereoInterleaved: ShortArray, frames: Int) {
        if (frames <= 0 || !enabled || muted) return
        synchronized(monitorLock) {
            for (i in 0 until frames) {
                if (monitorQueuedFrames >= monitorCapacityFrames) {
                    monitorReadPos = (monitorReadPos + 1) % monitorCapacityFrames
                    monitorQueuedFrames--
                }
                val dst = monitorWritePos * 2
                monitorRing[dst] = pcmStereoInterleaved[i * 2]
                monitorRing[dst + 1] = pcmStereoInterleaved[i * 2 + 1]
                monitorWritePos = (monitorWritePos + 1) % monitorCapacityFrames
                monitorQueuedFrames++
            }
        }
    }

    private fun drainMonitorPcm(target: ShortArray, maxFrames: Int): Int {
        synchronized(monitorLock) {
            val count = minOf(maxFrames, monitorQueuedFrames)
            for (i in 0 until count) {
                val src = monitorReadPos * 2
                target[i * 2] = monitorRing[src]
                target[i * 2 + 1] = monitorRing[src + 1]
                monitorReadPos = (monitorReadPos + 1) % monitorCapacityFrames
            }
            monitorQueuedFrames -= count
            return count
        }
    }

    /**
     * Resolve and cache thread-safe [I2sAudioPort], [AudioCodecControlPort], and [GpioInputPort]
     * handles on the emulation owner thread (required by `PortRegistry.find`), so background audio
     * threads, GUI controls, and HTTP endpoints never touch `PortRegistry` off the owner thread.
     */
    fun bindPorts(ports: Ports) {
        boundI2s = ports.find("soc-isp-setup.i2s-audio", I2sAudioPort::class.java)
        boundCodec = ports.find("wm8962.codec", AudioCodecControlPort::class.java)
            ?: ports.find("soc-spi.wm8962.codec", AudioCodecControlPort::class.java)
        boundGpio = ports.find("soc-gpio.inputs", GpioInputPort::class.java)
        boundSceneAudio = ports.find(SceneAudioProvider.PORT_NAME, SceneAudioProvider::class.java)
            ?: (ports.find("cmos", SensorPort::class.java) as? SceneAudioProvider)
        capture.syncRxSinkBindings()
    }

    private fun resolveSceneAudioPort(): SceneAudioProvider? =
        boundSceneAudio ?: runCatching {
            val ports = portsProvider() ?: return@runCatching null
            ports.find(SceneAudioProvider.PORT_NAME, SceneAudioProvider::class.java)
                ?: (ports.find("cmos", SensorPort::class.java) as? SceneAudioProvider)
        }.getOrNull()

    private fun resolveI2sPort(): I2sAudioPort? =
        boundI2s ?: runCatching { portsProvider()?.find("soc-isp-setup.i2s-audio", I2sAudioPort::class.java) }.getOrNull()

    private fun resolveCodecPort(): AudioCodecControlPort? =
        boundCodec ?: runCatching {
            val ports = portsProvider() ?: return@runCatching null
            ports.find("wm8962.codec", AudioCodecControlPort::class.java)
                ?: ports.find("soc-spi.wm8962.codec", AudioCodecControlPort::class.java)
        }.getOrNull()

    private fun resolveGpioPort(): GpioInputPort? =
        boundGpio ?: runCatching { portsProvider()?.find("soc-gpio.inputs", GpioInputPort::class.java) }.getOrNull()

    @Synchronized
    fun startIfEnabled(): AudioBackendMode {
        capture.startIfConfigured()
        if (closed.get() || !enabled) {
            backendMode = AudioBackendMode.DISABLED
            activeBackend = "DISABLED"
            actualLatencyMs = 0
            return backendMode
        }
        return start()
    }

    @Synchronized
    fun start(): AudioBackendMode {
        if (closed.get()) return AudioBackendMode.DISABLED
        enabled = true
        if (running.get()) return backendMode
        openLineOrDegrade()
        running.set(true)
        val thread = Thread(::pumpLoop, "fuji-audio-playback").apply {
            isDaemon = true
            start()
        }
        workerThread = thread
        return backendMode
    }

    @Synchronized
    fun stop() {
        running.set(false)
        val thread = workerThread
        workerThread = null
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(1000L) }
        }
        closeLineQuietly()
        backendMode = AudioBackendMode.DISABLED
        activeBackend = "DISABLED"
        actualLatencyMs = 0
        outputPeakLeft = 0.0
        outputPeakRight = 0.0
    }

    @Synchronized
    fun setEnabled(enable: Boolean): Map<String, Any?> {
        enabled = enable
        if (enable && !closed.get()) {
            start()
        } else {
            stop()
        }
        return status()
    }

    fun setVolume(newVolume: Double) {
        require(newVolume.isFinite() && newVolume in 0.0..1.0) { "volume must be within 0.0..1.0, got $newVolume" }
        volume = newVolume
    }

    fun setMuted(isMuted: Boolean) {
        muted = isMuted
        if (isMuted) {
            outputPeakLeft = 0.0
            outputPeakRight = 0.0
        }
    }

    /**
     * Toggle 3.5mm headphone jack (GPIO Bank 8 Pin 4, `KEY_CODE_HEADPHONE` `0x3F`, active-HIGH per
     * `GXUP0004.DAT @ 0x01812DC4`: `off=0, on=1`, 400 ms debounce) and drive into `soc-gpio.inputs` when mounted.
     */
    fun setHeadphonePlugged(plugged: Boolean): Boolean {
        headphonePlugged = plugged
        val gpio = resolveGpioPort()
        gpio?.setInputLevel(HEADPHONE_GPIO_PORT, HEADPHONE_GPIO_PIN, plugged)
        return gpio != null
    }

    /** Toggle 3.5mm external microphone jack (GPIO Bank 6 Pin 4, active-low) and drive into `soc-gpio.inputs` when mounted. */
    fun setExtMicPlugged(plugged: Boolean): Boolean {
        extMicPlugged = plugged
        val gpio = resolveGpioPort()
        gpio?.setInputLevel(EXT_MIC_GPIO_PORT, EXT_MIC_GPIO_PIN, !plugged)
        return gpio != null
    }

    /** Enumerates available host output, microphone input, and system-output loopback devices. */
    fun listDevices(): Map<String, Any?> = deviceCatalog.listDevices()

    /**
     * Atomically validates and applies a partial audio I/O configuration patch (shared contract for
     * `/api/audio`, `AudioIoPanel`, and `EmulatorConsole`) with hot device/latency/mode switching.
     */
    @Synchronized
    fun applyPatch(patch: Map<String, Any?>): Map<String, Any?> {
        require(patch.isNotEmpty() && patch.keys.all { it in PATCH_KEYS }) {
            "audio patch requires non-empty subset of $PATCH_KEYS"
        }

        // Phase 1: strict validation before mutating any state
        val nextEnabled = patch["enabled"]?.also { require(it is Boolean) { "enabled must be a boolean" } } as Boolean?
        val nextMuted = patch["muted"]?.also { require(it is Boolean) { "muted must be a boolean" } } as Boolean?
        val nextVolume = patch["volume"]?.let { raw ->
            require(raw is Number) { "volume must be a number" }
            val d = raw.toDouble()
            require(d.isFinite() && d in 0.0..1.0) { "volume must be within 0.0..1.0, got $d" }
            d
        }
        val nextOutputDeviceId = patch["outputDeviceId"]?.also {
            require(it is String) { "outputDeviceId must be a string" }
        } as String?
        val nextBufferMs = patch["bufferMs"]?.let { raw ->
            require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() == raw.toLong().toDouble()) {
                "bufferMs must be an integer"
            }
            val ms = raw.toInt()
            require(ms in AudioPlaybackConfig.MIN_BUFFER_MS..AudioPlaybackConfig.MAX_BUFFER_MS) {
                "bufferMs must be within ${AudioPlaybackConfig.MIN_BUFFER_MS}..${AudioPlaybackConfig.MAX_BUFFER_MS}, got $ms"
            }
            ms
        }
        val nextBackendPref = patch["backendPreference"]?.let { raw ->
            require(raw is String) { "backendPreference must be a string" }
            AudioBackendPreference.parse(raw)
        }
        val nextHeadphone = patch["headphonePlugged"]?.also {
            require(it is Boolean) { "headphonePlugged must be a boolean" }
        } as Boolean?
        val nextExtMic = patch["extMicPlugged"]?.also {
            require(it is Boolean) { "extMicPlugged must be a boolean" }
        } as Boolean?
        val nextInputMode = patch["inputMode"]?.let { raw ->
            require(raw is String) { "inputMode must be a string" }
            AudioInputMode.parse(raw)
        }
        val nextInputDeviceId = patch["inputDeviceId"]?.also {
            require(it is String) { "inputDeviceId must be a string" }
        } as String?
        val nextInputGainDb = patch["inputGainDb"]?.let { raw ->
            require(raw is Number) { "inputGainDb must be a number" }
            val d = raw.toDouble()
            require(d.isFinite() && d in HostAudioCapture.MIN_GAIN_DB..HostAudioCapture.MAX_GAIN_DB) {
                "inputGainDb must be within ${HostAudioCapture.MIN_GAIN_DB}..${HostAudioCapture.MAX_GAIN_DB}, got $d"
            }
            d
        }
        val nextInputMuted = patch["inputMuted"]?.also {
            require(it is Boolean) { "inputMuted must be a boolean" }
        } as Boolean?
        val nextInputMonitor = patch["inputMonitor"]?.also {
            require(it is Boolean) { "inputMonitor must be a boolean" }
        } as Boolean?
        val nextTestToneHz = patch["testToneHz"]?.let { raw ->
            require(raw is Number) { "testToneHz must be a number" }
            val d = raw.toDouble()
            require(d.isFinite() && d in HostAudioCapture.MIN_TEST_TONE_HZ..HostAudioCapture.MAX_TEST_TONE_HZ) {
                "testToneHz must be within ${HostAudioCapture.MIN_TEST_TONE_HZ}..${HostAudioCapture.MAX_TEST_TONE_HZ}, got $d"
            }
            d
        }

        // Phase 2: apply output settings (hot-reopening output stream only when device/buffer/backend changed)
        val outputNeedsReopen = (nextOutputDeviceId != null && nextOutputDeviceId != outputDeviceId) ||
            (nextBufferMs != null && nextBufferMs != bufferMs) ||
            (nextBackendPref != null && nextBackendPref != backendPreference)

        nextVolume?.let { volume = it }
        nextMuted?.let { setMuted(it) }
        nextOutputDeviceId?.let { outputDeviceId = it }
        nextBufferMs?.let { bufferMs = it }
        nextBackendPref?.let { backendPreference = it }

        if (nextEnabled != null) {
            if (nextEnabled) {
                if (running.get() && outputNeedsReopen) {
                    stop()
                }
                start()
            } else {
                enabled = false
                stop()
            }
        } else if (running.get() && outputNeedsReopen && !closed.get()) {
            stop()
            start()
        }

        // Phase 3: apply 3.5mm jack GPIO toggles
        nextHeadphone?.let { setHeadphonePlugged(it) }
        nextExtMic?.let { setExtMicPlugged(it) }

        // Phase 4: apply input / loopback capture settings
        if (
            nextInputMode != null ||
            nextInputDeviceId != null ||
            nextInputGainDb != null ||
            nextInputMuted != null ||
            nextInputMonitor != null ||
            nextTestToneHz != null ||
            nextBufferMs != null ||
            nextBackendPref != null
        ) {
            capture.configure(
                newMode = nextInputMode,
                newDeviceId = nextInputDeviceId,
                newGainDb = nextInputGainDb,
                newMuted = nextInputMuted,
                newMonitor = nextInputMonitor,
                newTestToneHz = nextTestToneHz,
                newBufferMs = nextBufferMs,
                newBackendPreference = nextBackendPref,
            )
        }

        return status()
    }

    /** Drain and process one available PCM chunk (or monitor chunk) immediately. */
    fun pumpOnce(): Boolean = synchronized(pumpLock) {
        val i2s = resolveI2sPort()
        val chunk = i2s?.drainPlaybackPcm(CHUNK_FRAMES)
        if (chunk != null) {
            val codec = resolveCodecPort()
            val route = codec?.outputRoute()
            val monFrames = if (capture.monitor && !capture.muted) drainMonitorPcm(mixShortScratch, chunk.frames) else 0
            val sourceSamples = if (monFrames > 0) {
                for (i in 0 until chunk.frames * 2) {
                    val base = chunk.samples[i].toInt()
                    val mon = if (i < monFrames * 2) mixShortScratch[i].toInt() else 0
                    mixShortScratch[i] = (base + mon).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                mixShortScratch
            } else {
                chunk.samples
            }
            val byteLen = renderToLittleEndianBytes(
                samples = sourceSamples,
                frames = chunk.frames,
                route = route,
                masterVolume = volume,
                masterMuted = muted,
                target = renderBuffer,
                peaksOut = peakScratch,
            )
            outputPeakLeft = peakScratch[0]
            outputPeakRight = peakScratch[1]
            writeRenderedBytes(byteLen)
            framesPlayed.addAndGet(chunk.frames.toLong())
            chunksPlayed.incrementAndGet()
            return true
        }

        // If I2S TX is idle but input monitoring is enabled, still pass monitor frames to output cleanly.
        val monFrames = if (capture.monitor && !capture.muted) drainMonitorPcm(mixShortScratch, CHUNK_FRAMES) else 0
        if (monFrames > 0) {
            val byteLen = renderToLittleEndianBytes(
                samples = mixShortScratch,
                frames = monFrames,
                route = null,
                masterVolume = volume,
                masterMuted = muted,
                target = renderBuffer,
                peaksOut = peakScratch,
            )
            outputPeakLeft = peakScratch[0]
            outputPeakRight = peakScratch[1]
            writeRenderedBytes(byteLen)
            framesPlayed.addAndGet(monFrames.toLong())
            chunksPlayed.incrementAndGet()
            return true
        }

        outputPeakLeft *= 0.75
        outputPeakRight *= 0.75
        if (outputPeakLeft < 1e-4) outputPeakLeft = 0.0
        if (outputPeakRight < 1e-4) outputPeakRight = 0.0
        return false
    }

    private fun writeRenderedBytes(byteLen: Int) {
        onRenderedPcm?.invoke(renderBuffer, byteLen)
        val line = activeLine
        if (line != null && backendMode == AudioBackendMode.ACTIVE_LINE) {
            try {
                line.write(renderBuffer, 0, byteLen)
            } catch (error: Throwable) {
                log.warn("Host audio output write failed ({}); degrading to silent mode", error.message ?: error.javaClass.simpleName)
                degradationReason = error.message ?: error.javaClass.simpleName
                closeLineQuietly()
                backendMode = AudioBackendMode.HEADLESS_SILENT
                activeBackend = "HEADLESS_SILENT"
                actualLatencyMs = 0
            }
        }
    }

    fun status(): Map<String, Any?> {
        val i2s = resolveI2sPort()
        val codec = resolveCodecPort()
        val stats = i2s?.playbackStats()
        val route = codec?.outputRoute()
        val rx = capture.rxStats()
        return linkedMapOf(
            "enabled" to enabled,
            "running" to running.get(),
            "backendMode" to backendMode.name,
            "backendPreference" to backendPreference.name,
            "activeBackend" to activeBackend,
            "actualLatencyMs" to actualLatencyMs,
            "degradationReason" to degradationReason,
            "sampleRateHz" to SAMPLE_RATE_HZ,
            "channels" to CHANNELS,
            "bitDepth" to 16,
            "volume" to volume,
            "muted" to muted,
            "bufferMs" to bufferMs,
            "outputDeviceId" to outputDeviceId,
            "outputDeviceName" to outputDeviceName,
            "outputPeakLeft" to outputPeakLeft,
            "outputPeakRight" to outputPeakRight,
            "framesPlayed" to framesPlayed.get(),
            "chunksPlayed" to chunksPlayed.get(),
            "headphonePlugged" to headphonePlugged,
            "extMicPlugged" to extMicPlugged,
            "inputMode" to capture.mode.name,
            "inputBackendMode" to capture.backendMode,
            "inputDeviceId" to capture.deviceId,
            "inputDeviceName" to capture.deviceName,
            "inputGainDb" to capture.gainDb,
            "inputMuted" to capture.muted,
            "inputMonitor" to capture.monitor,
            "testToneHz" to capture.testToneHz,
            "inputPeakLeft" to capture.peakLeft,
            "inputPeakRight" to capture.peakRight,
            "inputFramesCaptured" to capture.inputFramesCaptured,
            "inputOverruns" to capture.inputOverruns,
            "inputUnderruns" to capture.inputUnderruns,
            "inputDegradationReason" to capture.degradationReason,
            "i2sPortBound" to (i2s != null),
            "codecPortBound" to (codec != null),
            "rxStats" to rx.toMap(),
            "i2sStats" to stats?.let {
                linkedMapOf(
                    "totalTxFrames" to it.totalTxFrames,
                    "totalTxWords" to it.totalTxWords,
                    "droppedFrames" to it.droppedFrames,
                    "queuedFrames" to it.queuedFrames,
                    "sequence" to it.sequence,
                    "activeChannelMask" to it.activeChannelMask,
                    "lastNonZeroFrameTick" to it.lastNonZeroFrameTick,
                )
            },
            "codecRoute" to route?.let {
                linkedMapOf(
                    "activeSink" to it.activeSink.name,
                    "speakerEnabled" to it.speakerEnabled,
                    "speakerVolume" to it.speakerVolume,
                    "headphoneEnabled" to it.headphoneEnabled,
                    "headphoneVolumeLeft" to it.headphoneVolumeLeft,
                    "headphoneVolumeRight" to it.headphoneVolumeRight,
                    "dacMuted" to it.dacMuted,
                    "sysclkEnabled" to it.sysclkEnabled,
                    "dsp2Enabled" to it.dsp2Enabled,
                    "effectiveLeftGain" to it.effectiveLeftGain,
                    "effectiveRightGain" to it.effectiveRightGain,
                )
            },
        )
    }

    private fun openLineOrDegrade() {
        val rawBufferBytes = ((SAMPLE_RATE_HZ.toLong() * bufferMs) / 1000L).toInt() * BYTES_PER_FRAME
        val bufferBytes = maxOf(CHUNK_FRAMES * BYTES_PER_FRAME, rawBufferBytes)
        try {
            if (lineProvider !== JvmAudioLineProvider) {
                activeLine = lineProvider.open(AUDIO_FORMAT_48K_16BIT_STEREO, bufferBytes)
                backendMode = AudioBackendMode.ACTIVE_LINE
                activeBackend = "JAVASOUND"
                outputDeviceName = outputDeviceId.ifBlank { "Custom Audio Line" }
                actualLatencyMs = bufferMs
                degradationReason = null
            } else {
                val opened = deviceCatalog.openOutput(
                    preference = backendPreference,
                    deviceId = outputDeviceId,
                    format = AUDIO_FORMAT_48K_16BIT_STEREO,
                    bufferMs = bufferMs,
                    bufferBytes = bufferBytes,
                )
                activeLine = opened.line
                backendMode = AudioBackendMode.ACTIVE_LINE
                activeBackend = opened.backendName
                outputDeviceName = opened.deviceName
                actualLatencyMs = opened.actualLatencyMs
                degradationReason = null
            }
            if (backendPreference == AudioBackendPreference.AUTO && activeBackend.startsWith("JAVAX_SOUND")) {
                log.warn(
                    "[FALLBACK][AUDIO] Host audio playback fell back to {} (device='{}', 48 kHz 16-bit stereo, buffer={}ms, latency={}ms); native low-latency bridge unavailable",
                    activeBackend,
                    outputDeviceName,
                    bufferMs,
                    actualLatencyMs,
                )
            } else {
                log.info(
                    "Host audio playback started (backend={}, device='{}', 48 kHz 16-bit stereo, buffer={}ms, latency={}ms, volume={})",
                    activeBackend,
                    outputDeviceName,
                    bufferMs,
                    actualLatencyMs,
                    volume,
                )
            }
        } catch (error: Throwable) {
            activeLine = null
            backendMode = AudioBackendMode.HEADLESS_SILENT
            activeBackend = "HEADLESS_SILENT"
            outputDeviceName = ""
            actualLatencyMs = 0
            degradationReason = error.message ?: error.javaClass.simpleName
            log.warn("[FALLBACK][AUDIO] Host audio device unavailable ({}); falling back to HEADLESS_SILENT mode", degradationReason)
        }
    }

    private fun closeLineQuietly() {
        val line = activeLine
        activeLine = null
        if (line != null) {
            runCatching { line.close() }
        }
    }

    private fun pumpLoop() {
        NativeOsScheduler.tuneCurrentThread(NativeOsScheduler.ThreadRole.AUDIO_IO)
        while (running.get() && !closed.get()) {
            try {
                if (!pumpOnce()) {
                    idlePolls.incrementAndGet()
                    if (!NativeOsScheduler.sleepNanos(5_000_000L)) {
                        Thread.sleep(5L)
                    } else if (Thread.interrupted()) {
                        break
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (error: Throwable) {
                log.warn("Host audio pump error: {}", error.message ?: error.javaClass.simpleName)
                runCatching { Thread.sleep(20L) }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { capture.close() }
        stop()
    }
}

/**
 * Pure metadata catalog of GFX100S 2.13 embedded 48 kHz 16-bit stereo PCM waveforms located in
 * `section4.bin` (`0x01021000` load base). Zero copyrighted firmware bytes are embedded in source;
 * waveforms are read at runtime/test time only when the user's local decoded `section4.bin` exists.
 */
object FirmwareEmbeddedWaveforms {
    const val SECTION4_BASE_VA = 0x0102_1000L

    data class Entry(
        val id: String,
        val virtualAddress: Long,
        val byteLength: Int,
    ) {
        val fileOffset: Long get() = virtualAddress - SECTION4_BASE_VA
        val frameCount: Int get() = byteLength / 4
    }

    // UI beep table (section4 @ 0x017470E0)
    val UI_BEEP_OP = Entry("ui-beep-0", 0x016F_36E0L, 0x0000_2EE0)
    val UI_BEEP_CONFIRM = Entry("ui-beep-1", 0x016F_65C0L, 0x0000_2BC0)
    val UI_BEEP_FOCUS = Entry("ui-beep-2-focus", 0x0172_7F80L, 0x0000_2580)

    // Electronic shutter sound 1 full single-shot waveform (section4 @ 0x01747110 group A sub 2)
    val ESHUTTER_1_FULL = Entry("eshutter-1-full", 0x0173_08E0L, 0x0000_5440)
    val ESHUTTER_2_FULL = Entry("eshutter-2-full", 0x0172_A500L, 0x0000_3000)
    val ESHUTTER_3_FULL = Entry("eshutter-3-full", 0x0173_9240L, 0x0000_4F00)

    fun readRawBytesIfPresent(section4Bin: Path, entry: Entry): ByteArray? {
        if (!Files.isRegularFile(section4Bin)) return null
        val size = Files.size(section4Bin)
        val offset = entry.fileOffset
        if (offset < 0L || offset + entry.byteLength > size) return null
        val out = ByteArray(entry.byteLength)
        RandomAccessFile(section4Bin.toFile(), "r").use { raf ->
            raf.seek(offset)
            raf.readFully(out)
        }
        return out
    }

    fun peakAmplitude16(pcmLittleEndianBytes: ByteArray): Int {
        val bb = ByteBuffer.wrap(pcmLittleEndianBytes).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0
        while (bb.remaining() >= 2) {
            val sample = kotlin.math.abs(bb.short.toInt())
            if (sample > peak) peak = sample
        }
        return peak
    }
}
