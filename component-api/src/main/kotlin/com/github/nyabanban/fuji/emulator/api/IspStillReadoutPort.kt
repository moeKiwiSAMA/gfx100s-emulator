package com.github.nyabanban.fuji.emulator.api

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sensor samples captured before the IMX461 switches back to its live-view register set. */
fun interface IspStillReadoutPort {
    fun currentReadout(): IspStillReadout?

    companion object {
        const val PORT_ID = "soc-isp.still-readout"
    }
}

class IspStillReadout(
    val tick: Long,
    val width: Int,
    val height: Int,
    val gainR1024: Int,
    val gainG1024: Int,
    val gainB1024: Int,
    samples: ByteBuffer,
) {
    private val bayer = samples.slice().asReadOnlyBuffer()

    init {
        require(tick >= 0 && width > 0 && height > 0)
        val pixels = width.toLong() * height
        require(bayer.remaining().toLong() == pixels || bayer.remaining().toLong() == pixels * 2L)
    }

    fun copyBayerTo(destination: ByteBuffer): Int {
        val size = bayer.remaining()
        if (destination.remaining() < size) return 0
        destination.put(bayer.duplicate())
        return size
    }

    fun bayerSamples(): ByteBuffer = bayer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
}
