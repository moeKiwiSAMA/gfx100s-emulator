package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.IspStillReadout
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SensorPort
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Tracks the sensor integration separately from the later RECC_S processing of its two strips. */
internal class StillReadoutLatch {
    private var integration: LiveViewDmaEngine.ExposureParams? = null
    private var armed = false
    private var pending: IspStillReadout? = null
    var processing: IspStillReadout? = null
        private set

    fun currentIntegration(): LiveViewDmaEngine.ExposureParams? = integration

    fun observeIntegration(mode: Int?, exposure: LiveViewDmaEngine.ExposureParams) {
        // GXUP0004.DAT programs 0xAF / 0xB1 during still live view. The subsequent 0x40
        // readout timing is identical for exposures from 1/25 to 1/160 s.
        if (mode == 0xAF || mode == 0xB1) integration = exposure
    }

    fun capture(
        tick: Long,
        sensor: SensorPort?,
        lens: LensParameters?,
        overrideExposure: LiveViewDmaEngine.ExposureParams? = null,
    ) {
        if (armed) return
        armed = true
        val settings = overrideExposure ?: integration ?: return
        if (overrideExposure != null) integration = overrideExposure
        if (sensor == null || lens == null) return
        val samples = ByteBuffer.allocate(WIDTH * HEIGHT * 2).order(ByteOrder.LITTLE_ENDIAN)
        val size = try {
            sensor.exposeStill(Exposure(tick, settings.durationNanos, settings.iso, lens), samples)
        } catch (_: IllegalStateException) {
            return
        } catch (_: java.nio.BufferOverflowException) {
            return
        }
        if (size != WIDTH * HEIGHT && size != samples.capacity()) return
        samples.position(0).limit(size)
        pending = IspStillReadout(
            tick, WIDTH, HEIGHT, settings.gainR1024, settings.gainG1024, settings.gainB1024, samples,
        )
    }

    fun beginProcessing() {
        if (armed) {
            processing = pending
            pending = null
            armed = false
        }
    }

    fun endReadout() {
        armed = false
        pending = null
        processing = null
        integration = null
    }

    fun save(): Map<String, Any?>? {
        val exp = integration ?: return null
        return mapOf(
            "integration" to listOf(exp.durationNanos, exp.iso, exp.gainR1024, exp.gainG1024, exp.gainB1024),
            "armed" to armed,
            "pending" to encode(pending),
            "processing" to encode(processing),
        )
    }

    companion object {
        private const val WIDTH = 1024
        private const val HEIGHT = 576

        private fun encode(frame: IspStillReadout?): Map<String, Any>? {
            frame ?: return null
            return mapOf(
                "tick" to frame.tick,
                "gains" to listOf(frame.gainR1024, frame.gainG1024, frame.gainB1024),
                "samples" to Base64.getEncoder().encodeToString(frame.bayerSamples().let { source ->
                    ByteArray(source.remaining()).also { source.get(it) }
                }),
            )
        }

        private fun decode(value: Any?): IspStillReadout? {
            value ?: return null
            require(value is Map<*, *>)
            val gains = value["gains"] as List<*>
            require(gains.size == 3)
            val rgb = gains.map { (it as Number).toInt().also { g -> require(g in 64..8192) } }
            val encoded = value["samples"] as String
            require(encoded.length <= ((WIDTH * HEIGHT * 2 + 2) / 3) * 4)
            val samples = ByteBuffer.wrap(Base64.getDecoder().decode(encoded))
            return IspStillReadout((value["tick"] as Number).toLong(), WIDTH, HEIGHT, rgb[0], rgb[1], rgb[2], samples)
        }

        fun restore(value: Any?): StillReadoutLatch {
            val result = StillReadoutLatch()
            value ?: return result
            require(value is Map<*, *>)
            val exp = value["integration"] as List<*>
            require(exp.size == 5)
            val words = exp.map { (it as Number).toLong() }
            require(words[0] in 1L..256_000L && words[1] in 25L..25600L && words.drop(2).all { it in 64L..8192L })
            result.integration = LiveViewDmaEngine.ExposureParams(
                words[0], words[1].toInt(), words[2].toInt(), words[3].toInt(), words[4].toInt(),
            )
            result.armed = value["armed"] as Boolean
            result.pending = decode(value["pending"])
            require(result.pending == null || result.armed)
            result.processing = decode(value["processing"])
            return result
        }
    }
}
