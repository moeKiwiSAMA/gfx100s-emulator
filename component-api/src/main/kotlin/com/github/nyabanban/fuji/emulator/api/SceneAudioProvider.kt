package com.github.nyabanban.fuji.emulator.api

/**
 * Optional 48 kHz 16-bit stereo audio capability exposed by animated or sound-emitting CMOS
 * [SceneSource] implementations (such as `scene://milk-frog`) and surfaced on [Ports] under
 * [PORT_NAME] so host audio capture (`SCENE_AUDIO`) can feed both the emulated GFX100S I2S RX
 * microphone pipeline and optional host speaker monitoring.
 */
interface SceneAudioProvider {
    /** True when the active scene currently generates live audio (`false` when `audio=off` or silent). */
    val sceneAudioEnabled: Boolean

    /** Human-readable name of the active scene audio stream. */
    val sceneAudioName: String get() = "3D Scene Audio"

    /**
     * Synthesizes or fills [frames] of 48 kHz 16-bit interleaved stereo PCM into [pcmStereoInterleaved]
     * (length >= `frames * 2`), spatialized for [pose]. Returns the number of stereo frames written.
     */
    fun sampleAudioStereo48k(
        pcmStereoInterleaved: ShortArray,
        frames: Int,
        pose: CameraPose3D = CameraPose3D.IDENTITY,
    ): Int

    companion object {
        const val PORT_NAME: String = "cmos.scene-audio"
        const val SAMPLE_RATE_HZ: Int = 48_000
    }
}

/** Optional synthesized scene microphone addressed by the first PCM sample's camera tick (54 MHz). */
interface CameraTimedSceneAudioProvider : SceneAudioProvider {
    val hasCameraTimedAudio: Boolean
    fun sampleAudioAtTick(out: ShortArray, frames: Int, pose: CameraPose3D, tick: Long): Int
}
