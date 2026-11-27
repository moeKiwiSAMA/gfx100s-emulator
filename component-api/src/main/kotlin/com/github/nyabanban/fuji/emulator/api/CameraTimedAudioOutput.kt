package com.github.nyabanban.fuji.emulator.api

import java.util.TreeMap

/** A route valid at the requested camera tick up to the next register change. */
data class TimedAudioRoute(val route: AudioOutputRoute, val untilExclusive: Long)

interface CameraTimedAudioCodec : AudioCodecControlPort {
    fun outputRouteAt(tick: Long): TimedAudioRoute
}

/** Sampled codec state travels with the PCM until the host consumes it. */
data class RoutedAudioPcm(val pcm: AudioPcmChunk, val route: AudioOutputRoute?)

interface RoutedI2sOutput : I2sAudioPort {
    fun bindOutputCodec(codec: AudioCodecControlPort?)
    fun drainRoutedCodecPcm(maxFrames: Int): RoutedAudioPcm?
}

/** Owner-locked register transition history used when paced DMA delivers an entire burst at completion. */
class AudioRouteHistory(initial: AudioOutputRoute) {
    private companion object {
        const val MAX_DMA_LOOKBACK_TICKS = (1_048_576L / 4L) * (54_000_000L / 48_000L)
    }

    private val transitions = TreeMap<Long, AudioOutputRoute>()

    init {
        reset(initial)
    }

    fun reset(route: AudioOutputRoute) {
        transitions.clear()
        transitions[Long.MIN_VALUE] = route
    }

    fun record(tick: Long, route: AudioOutputRoute) {
        if (transitions.floorEntry(tick).value == route) return
        transitions[tick] = route
        // A 1 MiB DMA descriptor holds at most 262144 packed frames at 1125 camera ticks/frame.
        // Keep the preceding route as well, including across arbitrarily long steady-state intervals.
        val windowStart = (tick - MAX_DMA_LOOKBACK_TICKS).coerceAtLeast(0L)
        val baseline = transitions.floorEntry(windowStart)
        if (baseline.key > Long.MIN_VALUE) {
            transitions.headMap(baseline.key, true).clear()
            transitions[Long.MIN_VALUE] = baseline.value
        }
    }

    fun at(tick: Long): TimedAudioRoute = TimedAudioRoute(
        transitions.floorEntry(tick).value,
        transitions.higherKey(tick) ?: Long.MAX_VALUE,
    )
}
