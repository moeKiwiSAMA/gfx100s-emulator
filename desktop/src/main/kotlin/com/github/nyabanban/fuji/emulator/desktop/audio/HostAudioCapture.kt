package com.github.nyabanban.fuji.emulator.desktop.audio

import com.github.nyabanban.fuji.emulator.api.CameraPose3D
import com.github.nyabanban.fuji.emulator.api.CameraTimedI2sRxPort
import com.github.nyabanban.fuji.emulator.api.CameraTimedSceneAudioProvider
import com.github.nyabanban.fuji.emulator.api.I2sAudioPort
import com.github.nyabanban.fuji.emulator.api.SceneAudioProvider
import com.github.nyabanban.fuji.emulator.runtime.NativeOsScheduler
import org.slf4j.LoggerFactory
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** Snapshot of I2S RX ring statistics surfaced under `audio.status()["rxStats"]`. */
data class AudioRxStatsSnapshot(
    val totalRxFrames: Long = 0L,
    val queuedRxFrames: Int = 0,
    val rxOverruns: Long = 0L,
    val rxUnderruns: Long = 0L,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "totalRxFrames" to totalRxFrames,
        "queuedRxFrames" to queuedRxFrames,
        "rxOverruns" to rxOverruns,
        "rxUnderruns" to rxUnderruns,
    )
}

/**
 * Decoupled sink pushing captured 48 kHz 16-bit stereo PCM into the emulated SoC I2S RX ring.
 * Uses [I2sAudioPort.pushRxSamples] / [I2sAudioPort.rxStats] automatically when available on the
 * mounted `soc-isp-setup.i2s-audio` port, with safe fallback accounting otherwise.
 */
interface AudioRxSink {
    fun pushRxSamples(pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int = HostAudioPlayback.SAMPLE_RATE_HZ): Int
    fun rxStats(): AudioRxStatsSnapshot
    fun setCaptureActive(active: Boolean) {}
    fun setTimedSource(source: ((ShortArray, Int, Long) -> Boolean)?): Boolean = false
    fun setFallbackProvider(
        provider: ((channel: Int, pcmStereoInterleaved: ShortArray, frames: Int) -> Int)?,
    ) {}
}

/**
 * Reflection-safe adapter binding [I2sAudioPort.pushRxSamples] and [I2sAudioPort.rxStats] (from Track A1)
 * without requiring a compile-time lockstep across parallel branches.
 */
internal class I2sPortRxSinkAdapter(
    private val i2sProvider: () -> I2sAudioPort?,
) : AudioRxSink {
    private val localPushedFrames = AtomicLong(0L)

    @Volatile private var cachedClass: Class<*>? = null
    @Volatile private var cachedPushMethod: Method? = null
    @Volatile private var cachedStatsMethod: Method? = null

    private fun resolveMethods(port: I2sAudioPort) {
        val cls = port.javaClass
        if (cachedClass === cls) return
        val push = runCatching {
            I2sAudioPort::class.java.getMethod(
                "pushRxSamples",
                ShortArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull() ?: runCatching {
            cls.getMethod(
                "pushRxSamples",
                ShortArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).also { it.trySetAccessible() }
        }.getOrNull()
        val stats = runCatching {
            I2sAudioPort::class.java.getMethod("rxStats")
        }.getOrNull() ?: runCatching {
            cls.getMethod("rxStats").also { it.trySetAccessible() }
        }.getOrNull()
        cachedPushMethod = push
        cachedStatsMethod = stats
        cachedClass = cls
    }

    override fun pushRxSamples(pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int): Int {
        if (frames <= 0) return 0
        val port = i2sProvider() ?: return 0
        resolveMethods(port)
        val push = cachedPushMethod
        return if (push != null) {
            val accepted = (runCatching {
                push.invoke(port, pcmStereoInterleaved, frames, sampleRateHz, 16) as? Number
            }.getOrNull()?.toInt() ?: frames).coerceAtLeast(0)
            localPushedFrames.addAndGet(accepted.toLong())
            accepted
        } else {
            localPushedFrames.addAndGet(frames.toLong())
            frames
        }
    }

    override fun rxStats(): AudioRxStatsSnapshot {
        val port = i2sProvider()
        if (port != null) {
            resolveMethods(port)
            val statsMethod = cachedStatsMethod
            if (statsMethod != null) {
                val raw = runCatching { statsMethod.invoke(port) }.getOrNull()
                if (raw != null) {
                    val cls = raw.javaClass
                    fun readLong(getter: String): Long =
                        runCatching {
                            val m = cls.getMethod(getter)
                            m.trySetAccessible()
                            (m.invoke(raw) as? Number)?.toLong() ?: 0L
                        }.getOrDefault(0L)
                    fun readInt(getter: String): Int =
                        runCatching {
                            val m = cls.getMethod(getter)
                            m.trySetAccessible()
                            (m.invoke(raw) as? Number)?.toInt() ?: 0
                        }.getOrDefault(0)
                    return AudioRxStatsSnapshot(
                        totalRxFrames = readLong("getTotalRxFrames"),
                        queuedRxFrames = readInt("getQueuedRxFrames"),
                        rxOverruns = readLong("getRxOverruns"),
                        rxUnderruns = readLong("getRxUnderruns"),
                    )
                }
            }
        }
        return AudioRxStatsSnapshot(totalRxFrames = localPushedFrames.get())
    }

    override fun setTimedSource(source: ((ShortArray, Int, Long) -> Boolean)?): Boolean {
        val port = i2sProvider() as? CameraTimedI2sRxPort ?: return false
        port.setTimedRxSource(source)
        return source != null
    }

    override fun setCaptureActive(active: Boolean) {
        i2sProvider()?.setRxCaptureActive(active)
    }

    override fun setFallbackProvider(
        provider: ((channel: Int, pcmStereoInterleaved: ShortArray, frames: Int) -> Int)?,
    ) {
        i2sProvider()?.setRxFallbackProvider(provider)
    }
}

/**
 * Lock-free 48 kHz 16-bit stereo host audio input & system-output loopback capture engine feeding the
 * GFX100S I2S RX pipeline (`fuji-audio-capture` daemon thread).
 *
 * Supports:
 * - `DISABLED`
 * - `HOST_MIC` (microphone / line-in via native WASAPI/PipeWire-Pulse or JavaSound)
 * - `OUTPUT_LOOPBACK` (captures any PC audio output device via WASAPI loopback / PipeWire-Pulse `.monitor` / JavaSound loopback / `parec`)
 * - `TEST_TONE` (deterministic 48 kHz sine wave generator for offline/CI verification)
 *
 * Applies real-time dB gain (`-24..+24 dB`) with smooth `tanh` soft limiting, mute, live stereo L/R
 * peak VU metering, and optional low-latency monitor passthrough into [HostAudioPlayback].
 */
class HostAudioCapture(
    private val rxSink: AudioRxSink,
    private val deviceCatalog: AudioDeviceCatalog,
    private val inputLineProvider: AudioInputLineProvider? = null,
    private val onMonitorPcm: ((ShortArray, Int) -> Unit)? = null,
    private val sceneAudioProvider: (() -> SceneAudioProvider?)? = null,
    initialMode: AudioInputMode = AudioInputMode.DISABLED,
    initialDeviceId: String = "",
    initialGainDb: Double = 0.0,
    initialMuted: Boolean = false,
    initialMonitor: Boolean = false,
    initialTestToneHz: Double = DEFAULT_TEST_TONE_HZ,
    initialBufferMs: Int = AudioPlaybackConfig.DEFAULT_BUFFER_MS,
    initialBackendPreference: AudioBackendPreference = AudioBackendPreference.AUTO,
) : AutoCloseable {
    companion object {
        const val DEFAULT_TEST_TONE_HZ = 440.0
        const val MIN_TEST_TONE_HZ = 20.0
        const val MAX_TEST_TONE_HZ = 20_000.0
        const val MIN_GAIN_DB = -24.0
        const val MAX_GAIN_DB = 24.0
        const val CAPTURE_CHUNK_FRAMES = 480 // 10 ms @ 48 kHz
        /** Target RX ring headroom (~85 ms @ 48 kHz) for virtual-clock-synced synthetic TEST_TONE pacing. */
        internal const val TARGET_TEST_TONE_QUEUE_FRAMES = 4_096
        /** High-water cap (~256 ms @ 48 kHz) preventing low-RTR unbounded ring overruns on live host capture. */
        internal const val HOST_CAPTURE_HIGH_WATER_FRAMES = 12_288
        private const val SOFT_CLIP_KNEE = 0.85
        private const val TEST_TONE_BASE_AMPLITUDE = 0.25 // -12 dBFS nominal

        /** Converts decibels (`-24..+24 dB`) to linear gain factor. */
        fun dbToLinear(gainDb: Double): Double = 10.0.pow(gainDb / 20.0)

        /**
         * Smooth soft limiter: linear for `|x| <= 0.85`, smooth `tanh` knee approaching `±1.0` above `0.85`.
         */
        fun softLimitNormalized(x: Double): Double {
            val ax = abs(x)
            if (ax <= SOFT_CLIP_KNEE) return x
            val headroom = 1.0 - SOFT_CLIP_KNEE
            val excess = (ax - SOFT_CLIP_KNEE) / headroom
            val limited = SOFT_CLIP_KNEE + headroom * tanh(excess)
            return if (x >= 0.0) limited else -limited
        }

        /**
         * Applies linear gain + soft limiter (or mute) in place to interleaved stereo 16-bit PCM and
         * returns `(peakLeft, peakRight)` normalized to `0.0..1.0`.
         */
        internal fun processPcmInPlace(
            pcmStereoInterleaved: ShortArray,
            frames: Int,
            gainDb: Double,
            muted: Boolean,
        ): Pair<Double, Double> {
            val totalSamples = frames * 2
            if (muted || frames <= 0) {
                if (totalSamples > 0) pcmStereoInterleaved.fill(0, 0, totalSamples)
                return 0.0 to 0.0
            }
            val linearGain = dbToLinear(gainDb)
            var peakL = 0
            var peakR = 0
            var i = 0
            while (i < totalSamples) {
                val rawL = pcmStereoInterleaved[i] / 32768.0
                val rawR = pcmStereoInterleaved[i + 1] / 32768.0
                val outL = (softLimitNormalized(rawL * linearGain) * 32767.0).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val outR = (softLimitNormalized(rawR * linearGain) * 32767.0).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcmStereoInterleaved[i] = outL.toShort()
                pcmStereoInterleaved[i + 1] = outR.toShort()
                val absL = abs(outL)
                val absR = abs(outR)
                if (absL > peakL) peakL = absL
                if (absR > peakR) peakR = absR
                i += 2
            }
            return (peakL / 32768.0).coerceIn(0.0, 1.0) to (peakR / 32768.0).coerceIn(0.0, 1.0)
        }
    }

    private val log = LoggerFactory.getLogger(HostAudioCapture::class.java)
    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val captureLock = Any()

    @Volatile var mode: AudioInputMode = initialMode
        private set

    @Volatile var deviceId: String = initialDeviceId
        private set

    @Volatile var deviceName: String = ""
        private set

    @Volatile var gainDb: Double = initialGainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        private set

    @Volatile var muted: Boolean = initialMuted
        private set

    @Volatile var monitor: Boolean = initialMonitor
        private set

    @Volatile var testToneHz: Double = initialTestToneHz.coerceIn(MIN_TEST_TONE_HZ, MAX_TEST_TONE_HZ)
        private set

    @Volatile private var bufferMs: Int = initialBufferMs
    @Volatile private var backendPreference: AudioBackendPreference = initialBackendPreference

    @Volatile var backendMode: String = "DISABLED"
        private set

    @Volatile var degradationReason: String? = null
        private set

    @Volatile var actualLatencyMs: Int = 0
        private set

    @Volatile var peakLeft: Double = 0.0
        private set

    @Volatile var peakRight: Double = 0.0
        private set

    private val framesCaptured = AtomicLong(0L)
    private val overruns = AtomicLong(0L)
    private val underruns = AtomicLong(0L)

    private var workerThread: Thread? = null
    private var activeInputLine: AudioInputLine? = null
    private var tonePhaseRadians: Double = 0.0
    private val scratchPcm = ShortArray(CAPTURE_CHUNK_FRAMES * 2)
    private val pushScratchPcm = ShortArray(CAPTURE_CHUNK_FRAMES * 2)

    val inputFramesCaptured: Long get() = framesCaptured.get()
    val inputOverruns: Long get() = overruns.get()
    val inputUnderruns: Long get() = underruns.get()

    @Synchronized
    fun startIfConfigured() {
        if (closed.get() || mode == AudioInputMode.DISABLED) {
            stop()
            return
        }
        start()
    }

    @Synchronized
    fun start() {
        if (closed.get() || mode == AudioInputMode.DISABLED) {
            backendMode = "DISABLED"
            syncRxSinkBindings()
            return
        }
        if (running.get()) {
            syncRxSinkBindings()
            return
        }
        openInputOrDegrade()
        running.set(true)
        syncRxSinkBindings()
        val thread = Thread(::captureLoop, "fuji-audio-capture").apply {
            isDaemon = true
            start()
        }
        workerThread = thread
    }

    @Synchronized
    fun stop() {
        running.set(false)
        val thread = workerThread
        workerThread = null
        thread?.interrupt()
        if (thread != null) {
            LockSupport.unpark(thread)
        }
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(1000L) }
        }
        timedSceneBound = rxSink.setTimedSource(null)
        rxSink.setFallbackProvider(null)
        rxSink.setCaptureActive(false)
        closeLineQuietly()
        backendMode = "DISABLED"
        actualLatencyMs = 0
        peakLeft = 0.0
        peakRight = 0.0
    }

    @Volatile private var timedSceneBound = false

    /** Re-applies active capture state and on-demand TEST_TONE provider when ports are late-bound. */
    @Synchronized
    fun syncRxSinkBindings() {
        val active = running.get() && !closed.get() && mode != AudioInputMode.DISABLED
        timedSceneBound = rxSink.setTimedSource(if (active && mode == AudioInputMode.SCENE_AUDIO) ::fillTimedScene else null)
        if (!active) {
            rxSink.setFallbackProvider(null)
            rxSink.setCaptureActive(false)
            return
        }
        rxSink.setCaptureActive(true)
        when (mode) {
            AudioInputMode.TEST_TONE -> rxSink.setFallbackProvider(::fillTestToneOnDemand)
            AudioInputMode.SCENE_AUDIO -> rxSink.setFallbackProvider(::fillSceneAudioOnDemand)
            else -> rxSink.setFallbackProvider(null)
        }
    }

    @Synchronized
    fun configure(
        newMode: AudioInputMode? = null,
        newDeviceId: String? = null,
        newGainDb: Double? = null,
        newMuted: Boolean? = null,
        newMonitor: Boolean? = null,
        newTestToneHz: Double? = null,
        newBufferMs: Int? = null,
        newBackendPreference: AudioBackendPreference? = null,
    ) {
        newGainDb?.let {
            require(it.isFinite() && it in MIN_GAIN_DB..MAX_GAIN_DB) {
                "inputGainDb must be within $MIN_GAIN_DB..$MAX_GAIN_DB, got $it"
            }
        }
        newTestToneHz?.let {
            require(it.isFinite() && it in MIN_TEST_TONE_HZ..MAX_TEST_TONE_HZ) {
                "testToneHz must be within $MIN_TEST_TONE_HZ..$MAX_TEST_TONE_HZ, got $it"
            }
        }
        newBufferMs?.let {
            require(it in AudioPlaybackConfig.MIN_BUFFER_MS..AudioPlaybackConfig.MAX_BUFFER_MS) {
                "bufferMs must be within ${AudioPlaybackConfig.MIN_BUFFER_MS}..${AudioPlaybackConfig.MAX_BUFFER_MS}, got $it"
            }
        }

        val needsRestart = (newMode != null && newMode != mode) ||
            (newDeviceId != null && newDeviceId != deviceId) ||
            (newBufferMs != null && newBufferMs != bufferMs) ||
            (newBackendPreference != null && newBackendPreference != backendPreference)

        if (newMode != null) mode = newMode
        if (newDeviceId != null) deviceId = newDeviceId
        if (newGainDb != null) gainDb = newGainDb
        if (newMuted != null) muted = newMuted
        if (newMonitor != null) monitor = newMonitor
        if (newTestToneHz != null) testToneHz = newTestToneHz
        if (newBufferMs != null) bufferMs = newBufferMs
        if (newBackendPreference != null) backendPreference = newBackendPreference

        if (closed.get()) return
        if (mode == AudioInputMode.DISABLED) {
            stop()
            degradationReason = null
            deviceName = ""
        } else if (needsRestart || !running.get()) {
            stop()
            start()
        }
    }

    /**
     * Captures, processes, meters, and pushes one chunk synchronously into [rxSink].
     * Used by the capture daemon thread and deterministic unit tests.
     */
    fun captureOnce(): Int = captureOnceInternal(applyBackpressure = false)

    private fun captureOnceInternal(applyBackpressure: Boolean): Int {
        if (closed.get() || mode == AudioInputMode.DISABLED || (applyBackpressure && !running.get())) return 0
        // Query rxSink stats outside captureLock so fallback callbacks holding an I2S lock cannot invert lock order.
        val queuedFrames = if (applyBackpressure) rxSink.rxStats().queuedRxFrames else 0
        var framesToPush = 0
        synchronized(captureLock) {
            if (closed.get() || mode == AudioInputMode.DISABLED || (applyBackpressure && !running.get())) return 0
            val currentMode = mode
            if (applyBackpressure && currentMode == AudioInputMode.SCENE_AUDIO && timedSceneBound &&
                (sceneAudioProvider?.invoke() as? CameraTimedSceneAudioProvider)?.hasCameraTimedAudio == true) {
                // I2S requests file samples at its camera tick. Optional wall-clock monitoring uses a separate cursor.
                if (monitor && !muted) {
                    val n = sampleSceneAudio(scratchPcm, CAPTURE_CHUNK_FRAMES)
                    processPcmInPlace(scratchPcm, n, gainDb, false)
                    onMonitorPcm?.invoke(scratchPcm, n)
                }
                return 0
            }
            val isSynthetic = currentMode == AudioInputMode.TEST_TONE || currentMode == AudioInputMode.SCENE_AUDIO
            if (applyBackpressure && isSynthetic && queuedFrames >= TARGET_TEST_TONE_QUEUE_FRAMES) {
                // Guest is slower than wall-clock (low RTR) or RX DMA is idle: avoid overflowing
                // the I2S RX ring while still keeping VU meters and optional host monitor live.
                if (currentMode == AudioInputMode.TEST_TONE) {
                    generateTestTone(scratchPcm, CAPTURE_CHUNK_FRAMES, testToneHz)
                } else {
                    sampleSceneAudio(scratchPcm, CAPTURE_CHUNK_FRAMES)
                }
                val (pL, pR) = processPcmInPlace(scratchPcm, CAPTURE_CHUNK_FRAMES, gainDb, muted)
                peakLeft = pL
                peakRight = pR
                if (monitor && !muted) {
                    onMonitorPcm?.invoke(scratchPcm, CAPTURE_CHUNK_FRAMES)
                }
                return 0
            }
            val framesRead = when (currentMode) {
                AudioInputMode.DISABLED -> 0
                AudioInputMode.TEST_TONE -> {
                    generateTestTone(scratchPcm, CAPTURE_CHUNK_FRAMES, testToneHz)
                    CAPTURE_CHUNK_FRAMES
                }
                AudioInputMode.SCENE_AUDIO -> {
                    sampleSceneAudio(scratchPcm, CAPTURE_CHUNK_FRAMES)
                }
                AudioInputMode.HOST_MIC, AudioInputMode.OUTPUT_LOOPBACK -> {
                    val line = activeInputLine
                    if (line == null) {
                        underruns.incrementAndGet()
                        peakLeft *= 0.7
                        peakRight *= 0.7
                        return 0
                    }
                    try {
                        val n = line.readFrames(scratchPcm, CAPTURE_CHUNK_FRAMES, 40)
                        if (n < 0) {
                            throw IllegalStateException("Audio input stream closed (code=$n)")
                        }
                        if (n == 0) {
                            underruns.incrementAndGet()
                            peakLeft *= 0.7
                            peakRight *= 0.7
                            return 0
                        }
                        n
                    } catch (err: Throwable) {
                        if (!closed.get() && running.get()) {
                            log.warn("Host audio capture failed ({}); degrading to headless silent", err.message ?: err.javaClass.simpleName)
                            degradationReason = err.message ?: err.javaClass.simpleName
                            closeLineQuietly()
                            backendMode = "HEADLESS_SILENT"
                        }
                        peakLeft = 0.0
                        peakRight = 0.0
                        return 0
                    }
                }
            }
            if (framesRead <= 0) return 0

            val (pL, pR) = processPcmInPlace(scratchPcm, framesRead, gainDb, muted)
            peakLeft = pL
            peakRight = pR

            val shouldPush = !applyBackpressure ||
                currentMode == AudioInputMode.TEST_TONE ||
                currentMode == AudioInputMode.SCENE_AUDIO ||
                queuedFrames < HOST_CAPTURE_HIGH_WATER_FRAMES
            if (shouldPush && (!applyBackpressure || running.get())) {
                System.arraycopy(scratchPcm, 0, pushScratchPcm, 0, framesRead * 2)
                framesToPush = framesRead
            }
            framesCaptured.addAndGet(framesRead.toLong())

            if (monitor && !muted) {
                onMonitorPcm?.invoke(scratchPcm, framesRead)
            }
            if (framesToPush <= 0) return framesRead
        }
        if (!applyBackpressure || running.get()) {
            val pushed = rxSink.pushRxSamples(pushScratchPcm, framesToPush, HostAudioPlayback.SAMPLE_RATE_HZ)
            if (pushed < framesToPush) {
                overruns.addAndGet((framesToPush - pushed).toLong())
            }
        }
        return framesToPush
    }

    /**
     * Demand-driven phase-continuous `TEST_TONE` generator invoked by `I2sAudioPort.pullRxSamples`
     * whenever guest I2S RX DMA bursts faster than wall-clock (e.g., high-RTR WFI bursts), keeping
     * `rxUnderruns == 0` with zero phase clicks.
     */
    private fun fillTestToneOnDemand(channel: Int, out: ShortArray, frames: Int): Int = synchronized(captureLock) {
        if (closed.get() || mode != AudioInputMode.TEST_TONE || frames <= 0) return 0
        generateTestTone(out, frames, testToneHz)
        val (pL, pR) = processPcmInPlace(out, frames, gainDb, muted)
        peakLeft = pL
        peakRight = pR
        framesCaptured.addAndGet(frames.toLong())
        frames
    }

    private fun fillTimedScene(out: ShortArray, frames: Int, tick: Long): Boolean = synchronized(captureLock) {
        if (closed.get() || mode != AudioInputMode.SCENE_AUDIO) return false
        val provider = sceneAudioProvider?.invoke() as? CameraTimedSceneAudioProvider ?: return false
        if (!provider.hasCameraTimedAudio) return false
        val produced = provider.sampleAudioAtTick(out, frames, CameraPose3D.IDENTITY, tick)
        if (produced != frames) return false
        deviceName = provider.sceneAudioName
        val (left, right) = processPcmInPlace(out, frames, gainDb, muted)
        peakLeft = left
        peakRight = right
        framesCaptured.addAndGet(frames.toLong())
        true
    }

    private fun fillSceneAudioOnDemand(channel: Int, out: ShortArray, frames: Int): Int = synchronized(captureLock) {
        if (closed.get() || mode != AudioInputMode.SCENE_AUDIO || frames <= 0) return 0
        val produced = sampleSceneAudio(out, frames)
        val (pL, pR) = processPcmInPlace(out, produced, gainDb, muted)
        peakLeft = pL
        peakRight = pR
        framesCaptured.addAndGet(produced.toLong())
        produced
    }

    private fun sampleSceneAudio(target: ShortArray, frames: Int): Int {
        val provider = sceneAudioProvider?.invoke()
        if (provider != null && provider.sceneAudioEnabled) {
            deviceName = provider.sceneAudioName
            val n = runCatching { provider.sampleAudioStereo48k(target, frames, CameraPose3D.IDENTITY) }.getOrDefault(0)
            if (n > 0) return n.coerceAtMost(frames)
        }
        if (frames > 0) target.fill(0, 0, frames * 2)
        return frames
    }

    fun rxStats(): AudioRxStatsSnapshot = rxSink.rxStats()

    private fun generateTestTone(target: ShortArray, frames: Int, freqHz: Double) {
        val phaseStep = 2.0 * PI * freqHz / HostAudioPlayback.SAMPLE_RATE_HZ.toDouble()
        val amp = TEST_TONE_BASE_AMPLITUDE * 32767.0
        var phase = tonePhaseRadians
        var dst = 0
        for (i in 0 until frames) {
            val s = (sin(phase) * amp).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            target[dst++] = s
            target[dst++] = s
            phase += phaseStep
            if (phase >= 2.0 * PI) phase -= 2.0 * PI
        }
        tonePhaseRadians = phase
    }

    private fun openInputOrDegrade() {
        if (mode == AudioInputMode.TEST_TONE) {
            closeLineQuietly()
            backendMode = "TEST_TONE"
            deviceName = "Built-in Sine Generator (${testToneHz.roundToInt()} Hz)"
            actualLatencyMs = (CAPTURE_CHUNK_FRAMES * 1000) / HostAudioPlayback.SAMPLE_RATE_HZ
            degradationReason = null
            return
        }
        if (mode == AudioInputMode.SCENE_AUDIO) {
            closeLineQuietly()
            backendMode = "SCENE_AUDIO"
            deviceName = sceneAudioProvider?.invoke()?.sceneAudioName ?: "3D Scene Audio"
            actualLatencyMs = (CAPTURE_CHUNK_FRAMES * 1000) / HostAudioPlayback.SAMPLE_RATE_HZ
            degradationReason = null
            return
        }
        val rawBufferBytes = ((HostAudioPlayback.SAMPLE_RATE_HZ.toLong() * bufferMs) / 1000L).toInt() * HostAudioPlayback.BYTES_PER_FRAME
        val bufferBytes = maxOf(CAPTURE_CHUNK_FRAMES * HostAudioPlayback.BYTES_PER_FRAME * 2, rawBufferBytes)
        try {
            val opened = inputLineProvider?.open(
                mode = mode,
                deviceId = deviceId,
                sampleRateHz = HostAudioPlayback.SAMPLE_RATE_HZ,
                channels = HostAudioPlayback.CHANNELS,
                bufferMs = bufferMs,
            ) ?: deviceCatalog.openInput(
                preference = backendPreference,
                mode = mode,
                deviceId = deviceId,
                format = HostAudioPlayback.AUDIO_FORMAT_48K_16BIT_STEREO,
                bufferMs = bufferMs,
                bufferBytes = bufferBytes,
            )
            activeInputLine = opened.line
            backendMode = opened.backendMode
            deviceName = opened.deviceName
            actualLatencyMs = opened.actualLatencyMs
            degradationReason = null
            log.info(
                "Host audio capture started (mode={}, backend={}, device='{}', latency={}ms)",
                mode,
                backendMode,
                deviceName,
                actualLatencyMs,
            )
        } catch (err: Throwable) {
            activeInputLine = null
            backendMode = "HEADLESS_SILENT"
            deviceName = ""
            actualLatencyMs = 0
            degradationReason = err.message ?: err.javaClass.simpleName
            log.info("Host audio capture unavailable for mode={} ({}); degrading silently", mode, degradationReason)
        }
    }

    private fun closeLineQuietly() {
        val line = activeInputLine
        activeInputLine = null
        if (line != null) {
            runCatching { line.close() }
        }
    }

    private fun captureLoop() {
        NativeOsScheduler.tuneCurrentThread(NativeOsScheduler.ThreadRole.AUDIO_IO)
        val chunkNanos = (CAPTURE_CHUNK_FRAMES * 1_000_000_000L) / HostAudioPlayback.SAMPLE_RATE_HZ.toLong()
        var nextDeadlineNanos = System.nanoTime()
        while (running.get() && !closed.get()) {
            try {
                val n = captureOnceInternal(applyBackpressure = true)
                if (mode == AudioInputMode.TEST_TONE || mode == AudioInputMode.SCENE_AUDIO) {
                    val now = System.nanoTime()
                    nextDeadlineNanos = maxOf(nextDeadlineNanos + chunkNanos, now)
                    val waitNanos = nextDeadlineNanos - now
                    if (waitNanos > 0L) {
                        if (!NativeOsScheduler.sleepNanos(waitNanos)) {
                            LockSupport.parkNanos(waitNanos)
                        }
                        if (Thread.interrupted()) break
                    }
                } else if (n == 0) {
                    if (!NativeOsScheduler.sleepNanos(10_000_000L)) {
                        Thread.sleep(10L)
                    } else if (Thread.interrupted()) {
                        break
                    }
                    nextDeadlineNanos = System.nanoTime()
                } else {
                    nextDeadlineNanos = System.nanoTime()
                }
            } catch (_: InterruptedException) {
                break
            } catch (err: Throwable) {
                log.warn("Host audio capture loop error: {}", err.message ?: err.javaClass.simpleName)
                runCatching { Thread.sleep(20L) }
                nextDeadlineNanos = System.nanoTime()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
    }
}
