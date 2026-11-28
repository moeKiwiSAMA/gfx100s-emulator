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
import kotlin.test.assertContentEquals
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
    fun scalerF000ReadsAndWritesMapped34BitBuffers() {
        verifyF000Transfer(0x1_2000_0000L, 0x2_3000_0000L)
        verifyF000Transfer(0x2000_0000L, 0x1_3000_0000L)
        verifyF000Transfer(0x1_2000_0000L, 0x3000_0000L)
        verifyF000Transfer(0x1_0000_0000L, 0x2_0000_0000L)
        verifyF000Transfer(0xffff_f800L, 0x3000_0000L)
    }

    @Test
    fun scalerF000CompletesIrqWithoutTouchingUnmappedHighBuffers() {
        verifyF000Transfer(0x1_2000_0000L, 0x1_3000_0000L, destinationMapped = false)
        verifyF000Transfer(0x1_2000_0000L, 0x1_3000_0000L, destinationMapped = false, boundsQualified = false)
    }

    private fun verifyF000Transfer(src: Long, dst: Long, destinationMapped: Boolean = true, boundsQualified: Boolean = true) {
        val irqs = mutableListOf<Pair<Int, Boolean>>()
        val input = ByteArray(64 * 48 * 2) { i -> (20 + (i * 17) % 212).toByte() }
        val output = ByteArray(input.size)
        val dma = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                if (!boundsQualified) super<DmaMemory>.check(address, size, write)
                require(address == if (write) dst else src)
                require(size == input.size.toLong())
                require(!write || destinationMapped)
            }
            override fun read(address: Long, destination: ByteBuffer) {
                check(address, destination.remaining().toLong(), false)
                check(destinationMapped) { "The destination must be validated before source DMA" }
                destination.put(input)
            }
            override fun write(address: Long, source: ByteBuffer) {
                check(address, source.remaining().toLong(), true)
                source.get(output)
            }
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
        // Identity geometry makes any stale, truncated-address or unwritten pixel observable.
        b.access(seq++, 0xfff7f008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7f00cL, 4, true, (47L shl 16) or 63L)
        b.access(seq++, 0xfff7f010L, 4, true, 0L)
        b.access(seq++, 0xfff7f014L, 4, true, (47L shl 16) or 63L)
        b.access(seq++, 0xfff7f018L, 4, true, (47L shl 16) or 63L)
        b.access(seq++, 0xfff7f0c0L, 4, true, src ushr 32)
        b.access(seq++, 0xfff7f0c4L, 4, true, src and 0xffff_ffffL)
        b.access(seq++, 0xfff7f0c8L, 4, true, 128L)
        b.access(seq++, 0xfff7f0ccL, 4, true, dst ushr 32)
        b.access(seq++, 0xfff7f0d0L, 4, true, dst and 0xffff_ffffL)
        b.access(seq++, 0xfff7f0d4L, 4, true, 128L)
        b.access(seq++, 0xfff7f004L, 4, true, 1L)

        val due = requireNotNull(b.nextDeadline())
        nowTick = due
        b.beforeInstruction(due)
        assertContentEquals(if (destinationMapped) input else ByteArray(input.size), output)
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
        val leftY = dstBuf[(dstH / 2) * dstStride + 20 * 2].toInt() and 0xFF
        assertTrue(leftY > 180, "Expected cropped right-half bright luma > 180 on left side of dst, got $leftY")
    }

    @Test
    fun scalerF000IntegerFastPathMatchesDoubleReferenceForStockFacePreview() {
        val random = kotlin.random.Random(20261127)
        for (srcStride in listOf(2048, 2560)) {
            val srcSpan = 575 * srcStride + 2048
            val rawInput = random.nextBytes(srcSpan)
            // Inject all three Milbeaut black margin sentinel words at start of row 0.
            byteArrayOf(0, 0, 0, 0, 0, 0, -128, -128, -128, -128, 0, 0).copyInto(rawInput, 0)
            // Normalized reference copy matching ImageScalerE000.normalizeMilbeautBlackMarginWords(..., yyuv422 = true).
            val normalized = rawInput.copyOf()
            var p = 0
            while (p + 3 < normalized.size) {
                val b0 = normalized[p].toInt() and 0xff
                val b1 = normalized[p + 1].toInt() and 0xff
                val b2 = normalized[p + 2].toInt() and 0xff
                val b3 = normalized[p + 3].toInt() and 0xff
                if ((b0 or b1 or b2 or b3) == 0 ||
                    (b0 == 0x80 && b1 == 0x80 && b2 == 0 && b3 == 0)
                ) {
                    normalized[p] = 0
                    normalized[p + 1] = 0
                    normalized[p + 2] = 0x80.toByte()
                    normalized[p + 3] = 0x80.toByte()
                }
                p += 4
            }
            for (yyuvOut in listOf(false, true)) {
                val expected = ByteArray(640 * 360 * 2)
                fun samplePixel(x: Int, y: Int): Triple<Int, Int, Int> {
                    val row = y * srcStride
                    val uvOff = row + (x and 1.inv()) * 2
                    val yVal = normalized[uvOff + (x and 1)].toInt() and 0xff
                    val u = normalized[uvOff + 2].toInt() and 0xff
                    val v = normalized[uvOff + 3].toInt() and 0xff
                    return Triple(yVal, u, v)
                }
                fun bilinear(c00: Int, c10: Int, c01: Int, c11: Int, fx: Double, fy: Double): Int {
                    val top = c00 + (c10 - c00) * fx
                    val bot = c01 + (c11 - c01) * fx
                    return (top + (bot - top) * fy + 0.5).toInt().coerceIn(0, 255)
                }
                for (dy in 0 until 360) {
                    val sy = dy.toDouble() * 575.0 / 359.0
                    val y0 = sy.toInt().coerceIn(0, 575)
                    val y1 = minOf(y0 + 1, 575)
                    val fy = sy - y0
                    var dx = 0
                    while (dx < 640) {
                        fun res(x: Int): Triple<Int, Int, Int> {
                            val sx = x.toDouble() * 1023.0 / 639.0
                            val x0 = sx.toInt().coerceIn(0, 1023)
                            val x1 = minOf(x0 + 1, 1023)
                            val fx = sx - x0
                            val a = samplePixel(x0, y0)
                            val b = samplePixel(x1, y0)
                            val c = samplePixel(x0, y1)
                            val d = samplePixel(x1, y1)
                            return Triple(
                                bilinear(a.first, b.first, c.first, d.first, fx, fy),
                                bilinear(a.second, b.second, c.second, d.second, fx, fy),
                                bilinear(a.third, b.third, c.third, d.third, fx, fy),
                            )
                        }
                        val f0 = res(dx)
                        val f1 = res(dx + 1)
                        val u = (f0.second + f1.second) / 2
                        val v = (f0.third + f1.third) / 2
                        val out = dy * 1280 + dx * 2
                        expected[out] = f0.first.toByte()
                        expected[out + 1] = f1.first.toByte()
                        expected[out + 2] = u.toByte()
                        expected[out + 3] = v.toByte()
                        dx += 2
                    }
                }

                val srcAddr = 0x8999_c000L
                val dstAddr = 0x8a9d_ac00L
                val actual = ByteArray(expected.size)
                val dma = object : DmaMemory {
                    override fun check(address: Long, size: Long, write: Boolean) {
                        require(if (write) address == dstAddr && size == actual.size.toLong() else address == srcAddr && size == rawInput.size.toLong())
                    }
                    override fun read(address: Long, destination: ByteBuffer) {
                        check(address, destination.remaining().toLong(), false)
                        destination.put(rawInput)
                    }
                    override fun write(address: Long, source: ByteBuffer) {
                        check(address, source.remaining().toLong(), true)
                        source.get(actual)
                    }
                }
                val scaler = ImageScalerF000(ImageScalerF000.IRQ133_PROFILE)
                fun write(offset: Int, value: Long) = scaler.access(0xfff7f000L + offset, true, value)
                write(0x08, 0x100)
                write(0x0c, (575L shl 16) or 1023L)
                write(0x14, (575L shl 16) or 1023L)
                write(0x18, (359L shl 16) or 639L)
                write(0xc0, 0)
                write(0xc4, srcAddr)
                write(0xc8, srcStride.toLong())
                write(0xcc, 0)
                write(0xd0, dstAddr)
                write(0xd4, 1280L)
                write(0xd8, if (yyuvOut) 0x88L else 0L)
                write(0x04, 1)
                var completed = false
                scaler.beforeInstruction(ImageScalerF000.COMPLETION_DELAY_TICKS, dma) { completed = true }
                assertTrue(completed)
                assertContentEquals(expected, actual, "Mismatch for stride=$srcStride yyuv=$yyuvOut")
            }
        }
    }
}
