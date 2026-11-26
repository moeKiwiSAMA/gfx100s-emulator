package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest
import com.github.nyabanban.fuji.emulator.api.SensorPort
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ReccMovieTilesTest {
    @Test
    fun packsBothFieldsAtHighAddressAndPreservesTileRowPadding() {
        val base = 0x1_a000_0000L
        val stride = 24
        val output = ByteArray(2 * stride) { 0x55 }
        val dma = guestRam(base, output)

        // Four different rows of U,Y0,V,Y1. Give each of the two tiles a different chroma.
        val raster = ByteArray(4 * 4 * 2) { i -> (10 + i * 3).toByte() }
        val packer = ReccMovieTiles()
        assertTrue(packer.write(dma, base, 4, 4, 0, 2, stride, raster, 4, 4))
        assertTrue(packer.write(dma, base, 4, 4, 2, 2, stride, raster, 4, 4))
        val words = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until 4) {
            for (x in 0 until 4 step 2) {
                val index = y * 8 + x * 2
                val tile = words.getInt(y / 2 * stride + x * 4 + (y % 2) * 4)
                assertEquals((raster[index + 1].toInt() and 255) * 4, tile and 1023)
                assertEquals((raster[index + 3].toInt() and 255) * 4, (tile ushr 20) and 1023)
                val chroma = y / 2 * 16 + x * 2 + y % 2 * 2
                val average = ((raster[chroma].toInt() and 255) + (raster[chroma + 8].toInt() and 255) + 1) / 2
                assertEquals(average * 4, (tile ushr 10) and 1023)
            }
            if (y % 2 == 0) assertContentEquals(ByteArray(8) { 0x55 },
                output.copyOfRange(y / 2 * stride + 16, (y / 2 + 1) * stride))
        }
        val before = output.copyOf()
        assertFalse(packer.write(dma, base, 4, 4, 1, 2, stride, raster, 4, 4))
        assertFalse(packer.write(dma, base, 4, 4, 0, 2, 15, raster, 4, 4))
        assertContentEquals(before, output)
    }

    @Test
    fun upperAndLowerStripsShareOneExposureAndExtendTheFinalActiveRow() {
        val width = 32
        val stride = 144
        val base = 0x1_a496_8000L
        val output = ByteArray(24 * stride) { 0x55 }
        val dma = guestRam(base, output)
        val engine = LiveViewDmaEngine()
        var exposures = 0
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int = error("unexpected Bayer fallback")
            override fun tryExposeFusedIspYuv(
                request: LiveViewIspFusionRequest, outYOrPacked: ByteArray, outUvPlane: ByteArray?,
            ): Boolean {
                exposures++
                for (y in 0 until request.dstHeight) {
                    for (x in 0 until request.dstWidth step 2) {
                        val pos = y * request.dstStrideBytes + x * 2
                        outYOrPacked[pos] = 80
                        outYOrPacked[pos + 1] = (y / 4).toByte()
                        outYOrPacked[pos + 2] = 170.toByte()
                        outYOrPacked[pos + 3] = (y / 4).toByte()
                    }
                }
                return true
            }
        }
        val exposure = LiveViewDmaEngine.DEFAULT_EXPOSURE_PARAMS
        val color = IspColorPipeline()
        assertTrue(engine.renderMovieTileStrip(10L, base, width, 48, 0, 24, stride, sensor, dma, null, exposure, color))
        assertTrue(engine.renderMovieTileStrip(20L, base, width, 44, 24, 20, stride, sensor, dma, null, exposure, color))
        assertEquals(1, exposures)
        val packed = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until 48) {
            val word = packed.getInt(y / 2 * stride + y % 2 * 4)
            assertEquals((minOf(y, 43) * 576 / 44 / 4) * 4, word and 1023, "luma at row $y")
            assertEquals((if (y % 2 == 0) 80 else 170) * 4, (word ushr 10) and 1023)
        }
    }

    private fun guestRam(base: Long, output: ByteArray): DmaMemory = object : DmaMemory {
        override fun check(address: Long, size: Long, write: Boolean) {
            require(write && address >= base && size <= output.size - (address - base))
        }
        override fun read(address: Long, destination: ByteBuffer) = error("unexpected read")
        override fun write(address: Long, source: ByteBuffer) {
            source.get(output, (address - base).toInt(), source.remaining())
        }
    }
}
