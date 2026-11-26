package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for BUG-V3-2-4:
 * - `ImageScalerF000` handles 34-bit high-AXI (`>= 4 GiB`) src/dst addresses cleanly (completes IRQ 133 without throwing).
 * - `ImageScalerE000` high-AXI optical synthesis honors `Imx461ExposurePort` live exposure and `E010/E014` crop window.
 */
class ImageScalerHighAxiAndCropTest {
    private val catalog = AddressCatalog.bundled()

    @Test
    fun scalerF000Handles34BitHighAxiSourceAndDestinationWithoutThrowing() {
        val irqs = mutableListOf<Pair<Int, Boolean>>()
        val dma = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) = Unit
            override fun read(address: Long, destination: ByteBuffer) = Unit
            override fun write(address: Long, source: ByteBuffer) = Unit
        }
        var nowTick = 900_000_000L
        val ctx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5afe0L
            override fun lr() = 0x11fdb18L
            override fun tick() = nowTick
            override fun interruptSink() = object : InterruptSink {
                override fun setLine(irq: Int, high: Boolean) { irqs += irq to high }
            }
            override fun dmaMemory() = dma
        }
        val b = checkNotNull(
            CatalogRuntime(
                catalog,
                "discovery",
                Path.of("."),
                mapOf("discovery" to mapOf(ImageScalerF000.KEY to ImageScalerF000.IRQ133_PROFILE)),
            ).createBus(ctx),
        )
        var seq = 1L
        // Program 64x48 -> 64x48 with 34-bit high-AXI src (F0C0=1 -> 0x1_2000_0000) and dst (F0CC=1 -> 0x1_3000_0000)
        b.access(seq++, 0xfff7f008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7f00cL, 4, true, (47L shl 16) or 63L)
        b.access(seq++, 0xfff7f010L, 4, true, 0L)
        b.access(seq++, 0xfff7f014L, 4, true, (47L shl 16) or 63L)
        b.access(seq++, 0xfff7f018L, 4, true, (47L shl 16) or 63L)
        b.access(seq++, 0xfff7f0c0L, 4, true, 1L)
        b.access(seq++, 0xfff7f0c4L, 4, true, 0x2000_0000L)
        b.access(seq++, 0xfff7f0c8L, 4, true, 128L)
        b.access(seq++, 0xfff7f0ccL, 4, true, 1L)
        b.access(seq++, 0xfff7f0d0L, 4, true, 0x3000_0000L)
        b.access(seq++, 0xfff7f0d4L, 4, true, 128L)
        b.access(seq++, 0xfff7f004L, 4, true, 1L)

        val due = requireNotNull(b.nextDeadline())
        nowTick = due
        b.beforeInstruction(due)
        assertEquals(listOf(133 to true), irqs, "34-bit high-AXI F000 DMA must complete cleanly and raise IRQ 133")
    }

    @Test
    fun scalerE000HighAxiSynthesisHonorsLiveExposureAndCropWindow() {
        // Left half of 1024x576 sensor is dark (code12=100), right half is bright (code12=3000)
        val srcW = 1024
        val srcH = 576
        val raw = ByteArray(srcW * srcH * 2)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                val c12 = if (x >= srcW / 2) 3000 else 100
                bb.putShort((c12 shl 4).toShort())
            }
        }
        val exposures = mutableListOf<Exposure>()
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                exposures += exposure
                destination.put(raw)
                return raw.size
            }
        }
        val expPort = object : Imx461ExposurePort {
            override val hasExposureRegs: Boolean = true
            override val shr15: Int = 1500
            override val svr12: Int = 0
            override val vmax12: Int = 2000
            override val pgc12: Int = 128
            override val pgcSecondary12: Int = 0
            override val hcgEnabled: Boolean = false
            override val dgain8: Int = 0
        }
        val dstAddr = 0x3000_0000L
        val dstW = 160
        val dstH = 120
        val dstStride = dstW * 2
        val dstBuf = ByteArray(dstH * dstStride)
        val dma = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address == dstAddr && size <= dstBuf.size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) = error("unused")
            override fun write(address: Long, source: ByteBuffer) {
                source.get(dstBuf, 0, source.remaining())
            }
        }
        var nowTick = 800_000_000L
        val irqs = mutableListOf<Pair<Int, Boolean>>()
        val ctx = object : DiscoveryContext {
            override fun core() = 0
            override fun pc() = 0x5afe0L
            override fun lr() = 0x11fccd0L
            override fun tick() = nowTick
            override fun interruptSink() = object : InterruptSink {
                override fun setLine(irq: Int, high: Boolean) { irqs += irq to high }
            }
            override fun dmaMemory() = dma
            override fun sensorPort() = sensor
            override fun exposurePort() = expPort
        }
        val b = checkNotNull(
            CatalogRuntime(
                catalog,
                "discovery",
                Path.of("."),
                mapOf("discovery" to mapOf(ImageScalerE000.KEY to ImageScalerE000.IRQ132_PROFILE)),
            ).createBus(ctx),
        )
        var seq = 1L
        // Program 1024x576 input cropped to the RIGHT half [x=512..1023, y=0..575] (all bright!), high-AXI src E0C0=1
        b.access(seq++, 0xfff7e008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7e00cL, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7e010L, 4, true, 512L) // cropX=512, cropY=0
        b.access(seq++, 0xfff7e014L, 4, true, ((srcH - 1).toLong() shl 16) or (512L - 1L)) // cropW=512, cropH=576
        b.access(seq++, 0xfff7e018L, 4, true, ((dstH - 1).toLong() shl 16) or (dstW - 1).toLong())
        b.access(seq++, 0xfff7e0c0L, 4, true, 1L) // 34-bit high-AXI src
        b.access(seq++, 0xfff7e0c4L, 4, true, 0x1000_0000L)
        b.access(seq++, 0xfff7e0d0L, 4, true, (srcW * 2).toLong())
        b.access(seq++, 0xfff7e0d4L, 4, true, 0L)
        b.access(seq++, 0xfff7e0d8L, 4, true, dstAddr)
        b.access(seq++, 0xfff7e0e4L, 4, true, dstStride.toLong())
        b.access(seq++, 0xfff7e004L, 4, true, 1L)

        val due = requireNotNull(b.nextDeadline())
        nowTick = due
        b.beforeInstruction(due)

        assertTrue((132 to true) in irqs)
        val expectedExp = Imx461ExposurePort.computeEffectiveExposure(expPort)
        assertEquals(expectedExp.durationNanos, exposures.single().durationNanos, "Must forward live IMX461 durationNanos")
        assertEquals(expectedExp.iso, exposures.single().iso, "Must forward live IMX461 ISO")

        // Left side of destination must be bright because we cropped strictly into the bright right half!
        val leftY = dstBuf[(dstH / 2) * dstStride + 20 * 2 + 1].toInt() and 0xFF
        assertTrue(leftY > 180, "Expected cropped right-half bright luma > 180 on left side of dst, got $leftY")
    }
}
