package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.SensorPort
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class StillBayerStripTest {
    @Test
    fun splitReadoutPreservesCfaStrideBitDepthAndExposureAcrossAxiBanks() {
        val width = 16
        val height = 12
        val stride = width * 2 + 8
        val phases = intArrayOf(0x4444, 0x8888, 0xcccc, 0xffff)
        var exposures = 0
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                exposures++
                for (y in 0 until 576) {
                    for (x in 0 until 1024) destination.putShort(phases[(y % 2) * 2 + x % 2].toShort())
                }
                return destination.position()
            }
        }
        val engine = LiveViewDmaEngine()
        for ((index, base) in listOf(0x891995c0L, 0x1001995c0L, 0x1801995c0L).withIndex()) {
            val bits = if (index == 0) 16 else 14
            val data = ByteArray(stride * height) { 0x55 }
            val ram = object : DmaMemory {
                override fun check(address: Long, size: Long, write: Boolean) {
                    require(write && address >= base && address + size <= base + data.size)
                }
                override fun read(address: Long, destination: ByteBuffer) = error("Unexpected read")
                override fun write(address: Long, source: ByteBuffer) {
                    check(address, source.remaining().toLong(), true)
                    source.get(data, (address - base).toInt(), source.remaining())
                }
            }
            for ((first, count) in listOf(0 to 5, 5 to 7)) {
                assertTrue(engine.renderStillBayerStrip(
                    first.toLong(), base, width, height, first, count, stride, bits, sensor,
                    ram, null, LiveViewDmaEngine.DEFAULT_EXPOSURE_PARAMS,
                ))
            }
            assertEquals(index + 1, exposures)
            val view = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    assertEquals(phases[y % 2 * 2 + x % 2] ushr (16 - bits), view.getShort(y * stride + x * 2).toInt() and 0xffff)
                }
                for (padding in width * 2 until stride) assertEquals(0x55, data[y * stride + padding].toInt())
            }
        }
    }
}
