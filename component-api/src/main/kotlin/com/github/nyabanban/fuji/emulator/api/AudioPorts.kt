package com.github.nyabanban.fuji.emulator.api

/** Active analog output destination derived from audio codec control registers. */
enum class AudioSinkKind {
    MUTED,
    SPEAKER_MONO,
    HEADPHONE_STEREO,
    BOTH,
}

/** Snapshot of codec analog/digital output routing and normalized linear gains (0.0..1.0). */
data class AudioOutputRoute(
    val speakerEnabled: Boolean,
    val speakerVolume: Int,
    val headphoneEnabled: Boolean,
    val headphoneVolumeLeft: Int,
    val headphoneVolumeRight: Int,
    val dacMuted: Boolean,
    val sysclkEnabled: Boolean,
    val dsp2Enabled: Boolean,
    val effectiveLeftGain: Float,
    val effectiveRightGain: Float,
    val activeSink: AudioSinkKind,
)

/**
 * Hardware control-plane contract for an external audio codec (e.g., WM8962 on SPI4).
 * Pure component-api contract with zero host audio library dependencies.
 */
interface AudioCodecControlPort {
    /** Process one 16-bit serial control word and return the MISO response word (0..0xFFFF). */
    fun exchange16(mosi: Int): Int

    /** Read the current 16-bit value of a codec register or DSP2 RAM word (0..0x7FFF). */
    fun readRegister(address: Int): Int

    /** Derive the current hardware output route and gain state from codec registers. */
    fun outputRoute(): AudioOutputRoute

    /** Number of completed software resets (e.g., R15 writes). */
    fun resetCount(): Long

    /** Count of written words in DSP2 Program RAM (0x2000..0x29FF). */
    fun dsp2ProgramWriteCount(): Int

    /** Count of written words in DSP2 Data/Coefficient RAM (0x3000..0x3FFF). */
    fun dsp2DataWriteCount(): Int

    /** Count of written words in DSP2 Calibration RAM (0x4200..0x46FF). */
    fun dsp2CalibrationWriteCount(): Int
}

/** Immutable stereo-interleaved 16-bit PCM chunk captured from an I2S channel. */
data class AudioPcmChunk(
    val channel: Int,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val samples: ShortArray,
    val frames: Int,
    val sequence: Long,
    val tick: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioPcmChunk) return false
        return channel == other.channel &&
            sampleRateHz == other.sampleRateHz &&
            bitDepth == other.bitDepth &&
            frames == other.frames &&
            sequence == other.sequence &&
            tick == other.tick &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = channel
        result = 31 * result + sampleRateHz
        result = 31 * result + bitDepth
        result = 31 * result + frames
        result = 31 * result + sequence.hashCode()
        result = 31 * result + tick.hashCode()
        result = 31 * result + samples.contentHashCode()
        return result
    }
}

/** Deterministic counters for I2S PCM capture and playback ring accounting. */
data class AudioPlaybackStats(
    val totalTxFrames: Long,
    val totalTxWords: Long,
    val droppedFrames: Long,
    val queuedFrames: Int,
    val sequence: Long,
    val activeChannelMask: Int,
    val lastNonZeroFrameTick: Long?,
)

/** Counters for host-to-guest I2S RX capture ring accounting. */
data class AudioCaptureStats(
    val totalRxFrames: Long = 0L,
    val queuedRxFrames: Int = 0,
    val rxOverruns: Long = 0L,
    val rxUnderruns: Long = 0L,
)

/**
 * Hardware PCM audio stream port bridging dual SoC I2S controllers (0xFFF4F000 / 0xFFF78000)
 * to deterministic capture buffers and optional host audio playback backends.
 */
interface I2sAudioPort {
    /** Push interleaved 16-bit stereo frames (length >= frames * 2) from an I2S TX channel. */
    fun pushTxSamples(
        channel: Int,
        pcmStereoInterleaved: ShortArray,
        frames: Int,
        sampleRateHz: Int = 48_000,
        bitDepth: Int = 16,
        tick: Long = 0L,
    )

    /** Pull interleaved 16-bit stereo frames into an I2S RX channel; returns frames written. */
    fun pullRxSamples(
        channel: Int,
        pcmStereoInterleaved: ShortArray,
        frames: Int,
        sampleRateHz: Int = 48_000,
        bitDepth: Int = 16,
        tick: Long = 0L,
    ): Int

    /**
     * Push host-captured stereo-interleaved 16-bit PCM frames into the bounded I2S RX ring.
     * Non-blocking: overwrites oldest queued frames on overrun. Returns frames accepted.
     */
    fun pushRxSamples(
        pcmStereoInterleaved: ShortArray,
        frames: Int,
        sampleRateHz: Int = 48_000,
        bitDepth: Int = 16,
    ): Int = 0

    /** Drain up to [maxFrames] queued TX stereo frames for host playback or inspection. */
    fun drainPlaybackPcm(maxFrames: Int = 4096): AudioPcmChunk?

    /** Read current deterministic I2S TX/RX capture statistics. */
    fun playbackStats(): AudioPlaybackStats

    /** Read current host-to-guest I2S RX capture ring statistics. */
    fun rxStats(): AudioCaptureStats = AudioCaptureStats()

    /**
     * Inform the I2S RX ring whether an external host capture stream is actively feeding it.
     * When inactive (`false`), guest I2S RX DMA pulls deterministic idle silence without
     * recording spurious capture-buffer underruns.
     */
    fun setRxCaptureActive(active: Boolean) {}

    /**
     * Install or clear an optional on-demand RX sample provider invoked when guest I2S RX DMA
     * drains the RX ring faster than wall-clock (e.g., high-RTR WFI bursts in `TEST_TONE` mode).
     */
    fun setRxFallbackProvider(
        provider: ((channel: Int, pcmStereoInterleaved: ShortArray, frames: Int) -> Int)?,
    ) {}
}

/** Camera-clocked microphone inputs can be synthesized directly at the guest's I2S read timestamp. */
interface CameraTimedI2sRxPort {
    /** A false result delegates this read to the existing RX ring and its fallback source. */
    fun setTimedRxSource(source: ((out: ShortArray, frames: Int, tick: Long) -> Boolean)?)
}
