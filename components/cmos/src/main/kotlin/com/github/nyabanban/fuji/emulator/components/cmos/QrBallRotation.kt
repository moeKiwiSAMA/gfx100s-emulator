package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.SceneSourceRequest

/** The ball's material rotates about the world Y axis. The room, lights and depth stay fixed. */
internal class QrBallRotation private constructor(
    val moving: Boolean,
    private val initialTurns: Double,
    private val periodSeconds: Double,
    private val nanoClock: () -> Long,
) {
    private val startNanos = nanoClock()

    fun frame(): Long {
        if (!moving) return 0L
        val elapsed = (nanoClock() - startNanos).coerceAtLeast(0L)
        return elapsed / 1_000_000_000L * 60L + elapsed % 1_000_000_000L * 60L / 1_000_000_000L
    }

    fun turns(frame: Long): Float = ((initialTurns + frame / (periodSeconds * 60.0)) % 1.0).toFloat()

    companion object {
        fun parse(uri: String, clock: () -> Long = System::nanoTime): QrBallRotation {
            var moving = true
            var phase = 0.0
            var period = 8.0
            val keys = mutableSetOf<String>()
            for (field in uri.substringAfter('?', "").split('&').filter { it.isNotEmpty() }) {
                val key = field.substringBefore('=').lowercase()
                val value = field.substringAfter('=', "")
                require(keys.add(key)) { "Duplicate QR Ball parameter: $key" }
                when (key) {
                    "motion" -> moving = when (value.lowercase()) {
                        "on" -> true
                        "off" -> false
                        else -> throw IllegalArgumentException("QR Ball motion must be on or off")
                    }
                    "phase" -> phase = requireNotNull(value.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }) {
                        "QR Ball phase must be in 0..1 turns"
                    }
                    "period" -> period = requireNotNull(value.toDoubleOrNull()?.takeIf { it in 0.5..60.0 }) {
                        "QR Ball period must be in 0.5..60 seconds"
                    }
                    else -> throw IllegalArgumentException("Unknown QR Ball parameter: $key")
                }
            }
            return QrBallRotation(moving, phase, period, clock)
        }
    }
}

internal class RotatingQrBallSource(
    width: Int, height: Int, request: SceneSourceRequest, private val spin: QrBallRotation,
) : ProceduralOpticsSceneSource(OpticalScenePreset.QR_BALL, width, height, request.loop, spin, request.sourceRef),
    LiveStreamSceneSource {
    override val latestFrameSequence: Long get() = spin.frame() + 1L
    override val fusedFrameSequence: Long get() = spin.frame()
}
