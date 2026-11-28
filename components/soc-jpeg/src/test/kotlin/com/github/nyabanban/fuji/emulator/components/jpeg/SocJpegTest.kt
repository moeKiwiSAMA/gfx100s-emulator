package com.github.nyabanban.fuji.emulator.components.jpeg

import com.github.nyabanban.fuji.emulator.api.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.sqrt
import kotlin.test.*
import org.junit.jupiter.api.Test

class SocJpegTest {
    private class MockDmaMemory : DmaMemory {
        private val regions = LinkedHashMap<Long, ByteArray>()

        fun map(base: Long, size: Int) {
            regions[base] = ByteArray(size)
        }

        fun writeBytes(address: Long, data: ByteArray) {
            val (base, arr) = locate(address, data.size.toLong())
            System.arraycopy(data, 0, arr, (address - base).toInt(), data.size)
        }

        fun readBytes(address: Long, size: Int): ByteArray {
            val (base, arr) = locate(address, size.toLong())
            val off = (address - base).toInt()
            return arr.copyOfRange(off, off + size)
        }

        private fun locate(address: Long, size: Long): Pair<Long, ByteArray> {
            for ((base, arr) in regions) {
                if (address >= base && address + size <= base + arr.size.toLong()) {
                    return base to arr
                }
            }
            throw IllegalArgumentException("Unmapped DMA range 0x${address.toString(16)} size=$size")
        }

        override fun check(address: Long, size: Long, write: Boolean) {
            locate(address, size)
        }

        override fun read(address: Long, destination: ByteBuffer) {
            val len = destination.remaining()
            val (base, arr) = locate(address, len.toLong())
            destination.put(arr, (address - base).toInt(), len)
        }

        override fun write(address: Long, source: ByteBuffer) {
            val len = source.remaining()
            val (base, arr) = locate(address, len.toLong())
            source.get(arr, (address - base).toInt(), len)
        }
    }

    private class Harness(
        profile: String = SocJpegFactory.PROFILE,
        delayTicks: Long = 64L,
        dmaOverride: DmaMemory? = null,
    ) {
        var tick: Long = 1000L
        val dma = MockDmaMemory()
        val irqEvents = mutableListOf<Pair<Int, Boolean>>()
        val ports = object : Ports {
            private val map = LinkedHashMap<String, Any>()
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                map[name] = port
            }

            override fun <T : Any> find(name: String, type: Class<T>): T? {
                val p = map[name] ?: return null
                return type.cast(p)
            }

            override fun removeOwner(owner: String) {}
        }

        val component: StatefulComponent
        val mmio: ScheduledMmioDevice?
        val encoderMmio: ScheduledMmioDevice?

        init {
            ports.register("cpu", "cpu.memory", DmaMemory::class.java, dmaOverride ?: dma)
            ports.register(
                "soc-gic",
                "soc-gic.lines",
                InterruptSink::class.java,
                object : InterruptSink {
                    override fun setLine(irq: Int, high: Boolean) {
                        irqEvents += irq to high
                    }
                },
            )
            val tempDir = Files.createTempDirectory("soc-jpeg-test-")
            tempDir.toFile().deleteOnExit()
            val ctx = object : ComponentContext {
                override val directory: Path = tempDir
                override val ports: Ports = this@Harness.ports
                override val events: EventChannel = object : EventChannel {
                    override fun publish(event: ComponentEvent) {}
                    override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
                }
                override val clock: EmulationClock = EmulationClock { tick }
                override fun dataPath(relative: String): Path = tempDir.resolve(relative)
                override fun readData(relative: String): Map<String, Any?> = emptyMap()
            }
            val factory = SocJpegFactory()
            component = factory.create(
                ctx,
                mapOf("profile" to profile, "completionDelayTicks" to delayTicks),
            ) as StatefulComponent
            component.start()
            val decPortId = if (profile == SocJpegFactory.CODEC_PROFILE) SocJpegFactory.DECODER_V2_PORT_ID else SocJpegFactory.PORT_ID
            mmio = ports.find(decPortId, MmioDevice::class.java) as? ScheduledMmioDevice
            encoderMmio = ports.find(SocJpegFactory.ENCODER_PORT_ID, MmioDevice::class.java) as? ScheduledMmioDevice
        }
    }

    private fun makePatternImage(width: Int, height: Int, gray: Boolean = false): BufferedImage {
        val type = if (gray) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB
        val img = BufferedImage(width, height, type)
        val g = img.createGraphics()
        try {
            if (gray) {
                g.color = Color(40, 40, 40)
                g.fillRect(0, 0, width / 2, height / 2)
                g.color = Color(220, 220, 220)
                g.fillRect(width / 2, 0, width - width / 2, height / 2)
                g.color = Color(120, 120, 120)
                g.fillRect(0, height / 2, width / 2, height - height / 2)
                g.color = Color(180, 180, 180)
                g.fillRect(width / 2, height / 2, width - width / 2, height - height / 2)
            } else {
                g.color = Color(220, 40, 30)
                g.fillRect(0, 0, width / 2, height / 2)
                g.color = Color(30, 190, 60)
                g.fillRect(width / 2, 0, width - width / 2, height / 2)
                g.color = Color(30, 70, 230)
                g.fillRect(0, height / 2, width / 2, height - height / 2)
                g.color = Color(240, 210, 40)
                g.fillRect(width / 2, height / 2, width - width / 2, height - height / 2)
            }
        } finally {
            g.dispose()
        }
        return img
    }

    private fun encodeJpeg(img: BufferedImage): ByteArray {
        val baos = ByteArrayOutputStream()
        val writers = ImageIO.getImageWritersByFormatName("jpg")
        assertTrue(writers.hasNext())
        val writer = writers.next()
        try {
            MemoryCacheImageOutputStream(baos).use { out ->
                writer.output = out
                val param = writer.defaultWriteParam
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = 0.95f
                writer.write(null, IIOImage(img, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return baos.toByteArray()
    }

    private fun uyvyToRgb(bytes: ByteArray, width: Int, height: Int, strideBytes: Int): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val rowOff = y * strideBytes
            for (x in 0 until width) {
                val pairOff = rowOff + (x and 1.inv()) * 2
                val luma = bytes[pairOff + (x and 1)].toInt() and 0xFF
                val u = bytes[pairOff + 2].toInt() and 0xFF
                val v = bytes[pairOff + 3].toInt() and 0xFF
                val d = u - 128
                val e = v - 128
                val r = ((256 * luma + 359 * e + 128) shr 8).coerceIn(0, 255)
                val g = ((256 * luma - 88 * d - 183 * e + 128) shr 8).coerceIn(0, 255)
                val b = ((256 * luma + 454 * d + 128) shr 8).coerceIn(0, 255)
                out[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun rgbCorrelation(a: IntArray, b: IntArray): Double {
        require(a.size == b.size)
        var sumA = 0.0
        var sumB = 0.0
        val valsA = DoubleArray(a.size * 3)
        val valsB = DoubleArray(b.size * 3)
        for (i in a.indices) {
            valsA[i * 3] = ((a[i] ushr 16) and 0xFF).toDouble()
            valsA[i * 3 + 1] = ((a[i] ushr 8) and 0xFF).toDouble()
            valsA[i * 3 + 2] = (a[i] and 0xFF).toDouble()
            valsB[i * 3] = ((b[i] ushr 16) and 0xFF).toDouble()
            valsB[i * 3 + 1] = ((b[i] ushr 8) and 0xFF).toDouble()
            valsB[i * 3 + 2] = (b[i] and 0xFF).toDouble()
            sumA += valsA[i * 3] + valsA[i * 3 + 1] + valsA[i * 3 + 2]
            sumB += valsB[i * 3] + valsB[i * 3 + 1] + valsB[i * 3 + 2]
        }
        val meanA = sumA / valsA.size
        val meanB = sumB / valsB.size
        var num = 0.0
        var denA = 0.0
        var denB = 0.0
        for (i in valsA.indices) {
            val da = valsA[i] - meanA
            val db = valsB[i] - meanB
            num += da * db
            denA += da * da
            denB += db * db
        }
        val denom = sqrt(denA * denB)
        return if (denom == 0.0) 0.0 else num / denom
    }

    @Test
    fun resetHandshakeClearsStateAndSuppressesIrq136BeforeMaskConfigured() {
        val h = Harness()
        val dev = assertNotNull(h.mmio)
        h.irqEvents.clear()

        // Default mask is 0xFFFFFFFF (all masked)
        assertEquals(0xFFFF_FFFFL, dev.read(0x008L, 4))

        // Firmware 0x011FE82C reset sequence:
        // 1. Write 1 to 0x118 -> bit 16 (0x10000) in 0x004
        dev.write(0x118L, 4, 1L)
        assertEquals(0x0001_0000L, dev.read(0x004L, 4))
        assertTrue(h.irqEvents.isEmpty(), "Reset handshake must not assert IRQ 136")

        // 2. W1C clear bit 16 on 0x004
        dev.write(0x004L, 4, 0x0001_0000L)
        assertEquals(0L, dev.read(0x004L, 4))

        // 3. Write 0x100 to 0x000 -> soft reset, bit 8 self-clears
        dev.write(0x000L, 4, 0x100L)
        assertEquals(0L, dev.read(0x000L, 4) and 0x100L)
        assertEquals(0xFFFF_FFFFL, dev.read(0x008L, 4))

        // 4. Read 0x118 clears bit 2
        dev.write(0x118L, 4, 0x4L)
        assertEquals(0L, dev.read(0x118L, 4) and 0x4L)
    }

    @Test
    fun decodes728x546JpegToAlignedUyvyMemYcAndRaisesLevelIrq136() {
        val h = Harness(delayTicks = 64L)
        val dev = assertNotNull(h.mmio)
        val img = makePatternImage(728, 546)
        val jpeg = encodeJpeg(img)

        val memJpeg = 0x8800_0000L
        val memYc = 0x8900_0000L
        val alignedW = (728 + 15) and 15.inv() // 736
        val is420 = JpegUyvyCodec.detectIs420(jpeg)
        val vAlign = if (is420) 16 else 8
        val alignedH = (546 + vAlign - 1) and (vAlign - 1).inv()
        val expectedStride = alignedW * 2 // 1472
        val expectedBytes = expectedStride * alignedH

        h.dma.map(memJpeg, jpeg.size + 4096)
        h.dma.map(memYc, expectedBytes + 4096)
        h.dma.writeBytes(memJpeg, jpeg)

        // Reset & configure per 0x021E1044
        dev.write(0x118L, 4, 1L)
        dev.write(0x004L, 4, dev.read(0x004L, 4))
        dev.write(0x000L, 4, 0x100L)
        dev.write(0x008L, 4, 0x00FD_0020L) // unmask bits 4, 8, 17, 24
        dev.write(0x0A0L, 4, 0L)
        dev.write(0x100L, 4, memYc)
        dev.write(0x104L, 4, expectedBytes.toLong())
        dev.write(0x10CL, 4, memJpeg)
        dev.write(0x110L, 4, jpeg.size.toLong())
        dev.write(0x11CL, 4, 0x1L)
        // Wait: bit 0 of mode is 1 only for encode? Wait: 0x11C holds (cfg[0x2C] & 0xF) | (0x30<<4) | (0x31<<5)
        // For decode, mode bits 3:0 == 0
        dev.write(0x11CL, 4, 0x10L)
        dev.write(0x120L, 4, 0L)

        h.irqEvents.clear()
        dev.write(0x000L, 4, dev.read(0x000L, 4) or 1L)
        val due = h.tick + 64L
        assertEquals(due, dev.nextDeadline())

        // Before deadline: no IRQ, no completion
        h.tick = due - 1L
        dev.beforeInstruction(h.tick)
        assertTrue(h.irqEvents.isEmpty())
        assertEquals(0L, dev.read(0x004L, 4))

        // At deadline: UYVY written, bit 4 set, IRQ 136 asserted
        h.tick = due
        dev.beforeInstruction(h.tick)
        assertNull(dev.nextDeadline())
        assertEquals(listOf(136 to true), h.irqEvents)
        assertEquals(0x10L, dev.read(0x004L, 4))
        assertEquals(memYc + expectedBytes, dev.read(0x108L, 4))
        assertEquals(memJpeg + jpeg.size, dev.read(0x114L, 4))

        // ISR W1C ack clears IRQ 136
        dev.write(0x004L, 4, 0x10L)
        assertEquals(listOf(136 to true, 136 to false), h.irqEvents)

        val memYcData = h.dma.readBytes(memYc, expectedBytes)
        val decodedRgb = uyvyToRgb(memYcData, 728, 546, expectedStride)
        val refRgb = IntArray(728 * 546)
        img.getRGB(0, 0, 728, 546, refRgb, 0, 728)
        val corr = rgbCorrelation(decodedRgb, refRgb)
        assertTrue(corr >= 0.98, "Expected UYVY round-trip correlation >= 0.98, got $corr")
    }

    @Test
    fun supportsGrayscaleDownscaleAndRoiCrop() {
        // 1. Grayscale
        run {
            val h = Harness(delayTicks = 10L)
            val dev = assertNotNull(h.mmio)
            val grayImg = makePatternImage(64, 48, gray = true)
            val jpeg = encodeJpeg(grayImg)
            val memJpeg = 0x8800_0000L
            val memYc = 0x8900_0000L
            h.dma.map(memJpeg, jpeg.size + 256)
            h.dma.map(memYc, 64 * 48 * 2 + 4096)
            h.dma.writeBytes(memJpeg, jpeg)
            dev.write(0x008L, 4, 0x00FD_0020L)
            dev.write(0x100L, 4, memYc)
            dev.write(0x104L, 4, (64 * 48 * 2).toLong())
            dev.write(0x10CL, 4, memJpeg)
            dev.write(0x110L, 4, jpeg.size.toLong())
            dev.write(0x000L, 4, 1L)
            h.tick += 10L
            dev.beforeInstruction(h.tick)
            assertEquals(0x10L, dev.read(0x004L, 4))
            val out = h.dma.readBytes(memYc, 64 * 48 * 2)
            // Neutral chroma U/V near 128 (YYUV422: [Y0, Y1, Cb, Cr])
            val u = out[2].toInt() and 0xFF
            val v = out[3].toInt() and 0xFF
            assertTrue(u in 126..130 && v in 126..130, "Grayscale chroma expected near 128, got U=$u V=$v")
        }

        // 2. Downscale + ROI crop on color quadrants (128x128 -> crop bottom-right 64x64 -> downscale 1/2 -> 32x32)
        run {
            val h = Harness(delayTicks = 10L)
            val dev = assertNotNull(h.mmio)
            val img = makePatternImage(128, 128, gray = false)
            val jpeg = encodeJpeg(img)
            val memJpeg = 0x8800_0000L
            val memYc = 0x8900_0000L
            h.dma.map(memJpeg, jpeg.size + 256)
            h.dma.map(memYc, 64 * 64 * 2)
            h.dma.writeBytes(memJpeg, jpeg)
            dev.write(0x008L, 4, 0x00FD_0020L)
            dev.write(0x0A0L, 4, 1L) // 1/2 downscale
            dev.write(0x120L, 4, 1L) // ROI enable
            dev.write(0x124L, 4, 64L or (127L shl 16))
            dev.write(0x128L, 4, 64L or (127L shl 16))
            dev.write(0x100L, 4, memYc)
            dev.write(0x104L, 4, (32 * 32 * 2).toLong())
            dev.write(0x10CL, 4, memJpeg)
            dev.write(0x110L, 4, jpeg.size.toLong())
            dev.write(0x000L, 4, 1L)
            h.tick += 10L
            dev.beforeInstruction(h.tick)
            assertEquals(0x10L, dev.read(0x004L, 4))
            val out = h.dma.readBytes(memYc, 32 * 32 * 2)
            val rgb = uyvyToRgb(out, 32, 32, 32 * 2)
            // Bottom-right quadrant is yellow (R~240, G~210, B~40)
            val center = rgb[16 * 32 + 16]
            val r = (center ushr 16) and 0xFF
            val g = (center ushr 8) and 0xFF
            val b = center and 0xFF
            assertTrue(r > 200 && g > 170 && b < 80, "Expected cropped yellow quadrant, got ($r,$g,$b)")
        }
    }

    @Test
    fun corruptedJpegSmallBufferAndEncodeModeSetErrorBit17AndIrq136() {
        val h = Harness(delayTicks = 5L)
        h.irqEvents.clear()
        val dev = assertNotNull(h.mmio)
        val memJpeg = 0x8800_0000L
        val memYc = 0x8900_0000L
        h.dma.map(memJpeg, 4096)
        h.dma.map(memYc, 4096)

        // 1. Corrupted JPEG bytes
        h.dma.writeBytes(memJpeg, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x12, 0x34, 0x56, 0x78))
        dev.write(0x008L, 4, 0x00FD_0020L)
        dev.write(0x100L, 4, memYc)
        dev.write(0x104L, 4, 4096L)
        dev.write(0x10CL, 4, memJpeg)
        dev.write(0x110L, 4, 6L)
        dev.write(0x000L, 4, 1L)
        h.tick += 5L
        dev.beforeInstruction(h.tick)
        assertEquals(0x0002_0000L, dev.read(0x004L, 4))
        assertEquals(listOf(136 to true), h.irqEvents)
        dev.write(0x004L, 4, 0x0002_0000L)
        assertEquals(listOf(136 to true, 136 to false), h.irqEvents)

        // 2. Valid JPEG but MemYc capacity too small
        val jpeg = encodeJpeg(makePatternImage(64, 48))
        h.dma.writeBytes(memJpeg, jpeg)
        dev.write(0x104L, 4, 128L)
        dev.write(0x110L, 4, jpeg.size.toLong())
        dev.write(0x000L, 4, 1L)
        h.tick += 5L
        dev.beforeInstruction(h.tick)
        assertEquals(0x0002_0000L, dev.read(0x004L, 4))
        dev.write(0x004L, 4, 0x0002_0000L)

        // 3. Encode mode (0x11C low nibble == 1) explicitly rejected with bit 17
        dev.write(0x104L, 4, 64 * 48 * 2L)
        dev.write(0x11CL, 4, 1L)
        dev.write(0x000L, 4, 1L)
        h.tick += 5L
        dev.beforeInstruction(h.tick)
        assertEquals(0x0002_0000L, dev.read(0x004L, 4))
        assertEquals(3L, h.component.state()["errorCount"])
    }

    @Test
    fun refusesUnmappedHolesAndUnalignedAccesses() {
        val h = Harness()
        val dev = assertNotNull(h.mmio)
        for (badOffset in listOf(0x00CL, 0x020L, 0x09CL, 0x0A4L, 0x0FCL, 0x12CL, 0x002L)) {
            assertFailsWith<IllegalArgumentException> { dev.read(badOffset, 4) }
            assertFailsWith<IllegalArgumentException> { dev.write(badOffset, 4, 0L) }
        }
        assertFailsWith<IllegalArgumentException> { dev.read(0x000L, 2) }
        assertFailsWith<IllegalArgumentException> { dev.write(0x000L, 1, 0L) }
    }

    @Test
    fun snapshotRoundTripMidDecodePreservesPendingJobAndDisabledProfile() {
        val h1 = Harness(delayTicks = 64L)
        val dev1 = assertNotNull(h1.mmio)
        val jpeg = encodeJpeg(makePatternImage(64, 48))
        val memJpeg = 0x8800_0000L
        val memYc = 0x8900_0000L
        h1.dma.map(memJpeg, jpeg.size + 256)
        h1.dma.map(memYc, 64 * 48 * 2)
        h1.dma.writeBytes(memJpeg, jpeg)

        dev1.write(0x008L, 4, 0x00FD_0020L)
        dev1.write(0x100L, 4, memYc)
        dev1.write(0x104L, 4, (64 * 48 * 2).toLong())
        dev1.write(0x10CL, 4, memJpeg)
        dev1.write(0x110L, 4, jpeg.size.toLong())
        dev1.write(0x000L, 4, 1L)
        val snap = h1.component.saveSnapshot()

        val h2 = Harness(delayTicks = 64L)
        h2.dma.map(memJpeg, jpeg.size + 256)
        h2.dma.map(memYc, 64 * 48 * 2)
        h2.dma.writeBytes(memJpeg, jpeg)
        h2.component.restoreSnapshot(snap)
        h2.irqEvents.clear()
        val dev2 = assertNotNull(h2.mmio)
        assertEquals(h1.tick + 64L, dev2.nextDeadline())
        h2.tick += 64L
        dev2.beforeInstruction(h2.tick)
        assertEquals(0x10L, dev2.read(0x004L, 4))
        assertEquals(listOf(136 to true), h2.irqEvents)

        val disabled = Harness(profile = "disabled")
        assertNull(disabled.mmio)
        assertTrue(disabled.component.additionalMmioPorts().isEmpty())
        assertTrue(disabled.component.interruptRoutes().isEmpty())
        val disabledSnap = disabled.component.saveSnapshot()
        disabled.component.restoreSnapshot(disabledSnap)

        // Opt-in snapshot restore upgrade from disabled -> soc-jpeg-decoder-experimental-v1
        val upgraded = Harness(profile = SocJpegFactory.PROFILE, delayTicks = 64L)
        upgraded.component.restoreSnapshot(disabledSnap)
        val upgradedDev = assertNotNull(upgraded.mmio)
        assertEquals(0xFFFF_FFFFL, upgradedDev.read(0x008L, 4))
        assertEquals(0L, upgraded.component.state()["decodeCount"])

        // Downgrade from enabled -> disabled must be rejected
        assertFailsWith<IllegalArgumentException> {
            disabled.component.restoreSnapshot(upgraded.component.saveSnapshot())
        }
    }

    @Test
    fun chunkedDmaDecodesBitstreamLargerThan16MiBWithinPerCallLimit() {
        // Enforce Unicorn's 16 MiB per-call DMA read/write cap on the mock memory
        val maxSingleCall = 16 * 1024 * 1024
        val jpeg = encodeJpeg(makePatternImage(64, 48))
        val paddedLen = 17 * 1024 * 1024 // > 16 MiB bitstream span
        val memJpeg = 0x3a78_7000L
        val memYc = 0x2e55_a000L
        val boundedDma = object : DmaMemory {
            private val jpegBuf = ByteArray(paddedLen).also { System.arraycopy(jpeg, 0, it, 0, jpeg.size) }
            val ycBuf = ByteArray(64 * 48 * 2)
            override fun check(address: Long, size: Long, write: Boolean) {
                if (!write) require(address == memJpeg && size == paddedLen.toLong())
                else require(address == memYc && size <= ycBuf.size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) {
                val len = destination.remaining()
                require(len <= maxSingleCall) { "Single DMA read exceeded 16 MiB: $len" }
                destination.put(jpegBuf, (address - memJpeg).toInt(), len)
            }
            override fun write(address: Long, source: ByteBuffer) {
                val len = source.remaining()
                require(len <= maxSingleCall) { "Single DMA write exceeded 16 MiB: $len" }
                source.get(ycBuf, (address - memYc).toInt(), len)
            }
        }
        val h = Harness(delayTicks = 4L, dmaOverride = boundedDma)
        val dev = assertNotNull(h.mmio)
        dev.write(0x008L, 4, 0x00FD_0020L)
        dev.write(0x100L, 4, memYc)
        dev.write(0x104L, 4, (64 * 48 * 2).toLong())
        dev.write(0x10CL, 4, memJpeg)
        dev.write(0x110L, 4, paddedLen.toLong())
        dev.write(0x000L, 4, 1L)
        h.tick += 4L
        dev.beforeInstruction(h.tick)
        assertEquals(1L, h.component.state()["decodeCount"])
        assertEquals(0L, h.component.state()["errorCount"])
        assertEquals(0x10L, dev.read(0x004L, 4))
    }

    @Test
    fun codecV2EncodesUyvyOnIrq135AndRoundTripsThroughDecoderOnIrq136() {
        val h = Harness(profile = SocJpegFactory.CODEC_PROFILE, delayTicks = 32L)
        val enc = assertNotNull(h.encoderMmio)
        val dec = assertNotNull(h.mmio)
        assertEquals(listOf(SocJpegFactory.DECODER_V2_PORT_ID, SocJpegFactory.ENCODER_PORT_ID), h.component.additionalMmioPorts())
        assertEquals(listOf(InterruptRoute("soc-gic.lines", 135..136)), h.component.interruptRoutes())
        h.irqEvents.clear()

        // Create packed UYVY source buffer from color test chart (320x240, pitch = 640)
        val width = 320
        val height = 240
        val pitch = width * 2
        val refImg = makePatternImage(width, height, gray = false)
        val refJpeg = encodeJpeg(refImg)
        val decodedSeed = JpegUyvyCodec.decodeToMemYc(refJpeg, 0L, 0, false, 0, 0, 0, 0) as JpegUyvyCodec.DecodeResult.Success
        val srcUyvy = decodedSeed.uyvyBytes

        val srcYcAddr = 0x8800_0000L
        val dstJpegAddr = 0x8900_0000L
        val decYcAddr = 0x8A00_0000L
        val maxJpegBytes = 256 * 1024
        h.dma.map(srcYcAddr, srcUyvy.size + 4096)
        h.dma.map(dstJpegAddr, maxJpegBytes)
        h.dma.map(decYcAddr, srcUyvy.size + 4096)
        h.dma.writeBytes(srcYcAddr, srcUyvy)

        // Firmware 0x021E15FC / 0x021E173C / 0x021E16D8 sequence on 0xFFFA0000
        enc.write(0x118L, 4, 1L) // DMA stop
        enc.write(0x000L, 4, 0x100L) // soft reset
        assertEquals(0L, enc.read(0x000L, 4) and 0x100L, "Soft reset bit 8 must self-clear for 0x021E15E4 poll")
        assertEquals(0xFFFF_FFFFL, enc.read(0x028L, 4))
        assertTrue(h.irqEvents.isEmpty())

        enc.write(0x00CL, 4, 0x000A_0000L)
        enc.write(0x040L, 4, 0L) // 4:2:2 UYVY
        enc.write(0x044L, 4, (height.toLong() shl 16) or width.toLong())
        enc.write(0x048L, 4, 0x22L) // qscale
        enc.write(0x080L, 4, 0L) // DRI
        // Load 2x16 words quantization tables via 0x084/0x08C
        enc.write(0x084L, 4, 0x100L) // luma
        repeat(16) { idx -> enc.write(0x08CL, 4, 0x1010_1010L + idx) }
        enc.write(0x084L, 4, 0x101L) // chroma
        repeat(16) { idx -> enc.write(0x08CL, 4, 0x1212_1212L + idx) }
        enc.write(0x100L, 4, srcYcAddr)
        enc.write(0x10CL, 4, dstJpegAddr)
        enc.write(0x110L, 4, maxJpegBytes.toLong())
        enc.write(0x11CL, 4, 1L) // ENC_MODE = 1
        enc.write(0x120L, 4, 0L)
        enc.write(0x12CL, 4, (height.toLong() shl 16) or width.toLong())
        enc.write(0x130L, 4, decodedSeed.strideBytes.toLong())
        enc.write(0x028L, 4, 0xFFFD_FFEFL) // unmask bit 4 (ENC_DONE) and bit 17 (error)

        // Start encode
        enc.write(0x000L, 4, 1L)
        val due = h.tick + 32L
        assertEquals(due, enc.nextDeadline())
        h.tick = due
        enc.beforeInstruction(h.tick)

        assertNull(enc.nextDeadline())
        assertEquals(0L, enc.read(0x000L, 4) and 1L)
        assertEquals(0x10L, enc.read(0x024L, 4))
        assertEquals(listOf(135 to true), h.irqEvents)
        val outBytes = enc.read(0x094L, 4).toInt()
        assertTrue(outBytes in 512..maxJpegBytes, "Expected valid JPEG byte length, got $outBytes")
        assertEquals(dstJpegAddr + outBytes, enc.read(0x114L, 4))
        val encodedJpeg = h.dma.readBytes(dstJpegAddr, outBytes)
        assertEquals(0xFF.toByte(), encodedJpeg[0])
        assertEquals(0xD8.toByte(), encodedJpeg[1])
        assertEquals(0xFF.toByte(), encodedJpeg[outBytes - 2])
        assertEquals(0xD9.toByte(), encodedJpeg[outBytes - 1])

        // W1C ack clears GIC IRQ 135
        enc.write(0x024L, 4, 0x10L)
        assertEquals(listOf(135 to true, 135 to false), h.irqEvents)
        assertEquals(1L, h.component.state()["encodeCount"])
        assertEquals(0L, h.component.state()["encoderErrorCount"])

        // Loopback decode the hardware-encoded JPEG through decoder (0xFFFA1000, GIC IRQ 136)
        h.irqEvents.clear()
        dec.write(0x008L, 4, 0x00FD_0020L)
        dec.write(0x100L, 4, decYcAddr)
        dec.write(0x104L, 4, srcUyvy.size.toLong())
        dec.write(0x10CL, 4, dstJpegAddr)
        dec.write(0x110L, 4, outBytes.toLong())
        dec.write(0x000L, 4, 1L)
        h.tick += 32L
        dec.beforeInstruction(h.tick)

        assertEquals(0x10L, dec.read(0x004L, 4))
        assertEquals(listOf(136 to true), h.irqEvents)
        val roundTripUyvy = h.dma.readBytes(decYcAddr, srcUyvy.size)
        val roundTripRgb = uyvyToRgb(roundTripUyvy, width, height, pitch)
        val refRgb = IntArray(width * height)
        refImg.getRGB(0, 0, width, height, refRgb, 0, width)
        val corr = rgbCorrelation(roundTripRgb, refRgb)
        assertTrue(corr >= 0.96, "Expected encode->decode UYVY round-trip correlation >= 0.96, got $corr")
    }

    @Test
    fun onlyQuantizedRetryOfTheSameSourceRecoversOutputOverflow() {
        val h = Harness(profile = SocJpegFactory.CODEC_PROFILE, delayTicks = 8L)
        val enc = assertNotNull(h.encoderMmio)
        val width = 160
        val height = 120
        val input = kotlin.random.Random(7).nextBytes(width * height * 2)
        val src = 0x8800_0000L
        val dst = 0x8900_0000L
        h.dma.map(src, input.size)
        h.dma.map(dst, 131072)
        h.dma.writeBytes(src, input)
        enc.write(0x100, 4, src)
        enc.write(0x10c, 4, dst)
        enc.write(0x044, 4, (height.toLong() shl 16) or width.toLong())

        fun submit(scale: Long, capacity: Long): Long {
            enc.write(0x024, 4, 0x20010L)
            enc.write(0x048, 4, scale)
            enc.write(0x110, 4, capacity)
            enc.write(0x000, 4, 1)
            h.tick += 8
            enc.beforeInstruction(h.tick)
            return enc.read(0x094, 4)
        }
        val capacity = submit(1, 131072) - 1
        assertTrue(capacity > 1000)
        submit(1, capacity)
        assertEquals(0x20010L, enc.read(0x024, 4) and 0x20010)
        assertEquals(1L, h.component.state()["encoderUnrecoveredErrorCount"])
        val pending = h.component.saveSnapshot()
        val bytes = submit(15, capacity)
        assertTrue(bytes in 1..capacity)
        assertEquals(0x10L, enc.read(0x024, 4) and 0x20010)
        assertEquals(1L, h.component.state()["encoderErrorCount"])
        assertEquals(1L, h.component.state()["encoderRecoveredOverflowCount"])
        assertEquals(0L, h.component.state()["encoderUnrecoveredErrorCount"])

        h.component.restoreSnapshot(pending)
        input[3] = (input[3].toInt() xor 0xff).toByte()
        h.dma.writeBytes(src, input)
        submit(15, capacity)
        assertEquals(0L, h.component.state()["encoderRecoveredOverflowCount"])
        assertEquals(1L, h.component.state()["encoderUnrecoveredErrorCount"])
        h.component.restoreSnapshot(pending)
        submit(1, 131072)
        assertEquals(0L, h.component.state()["encoderRecoveredOverflowCount"])
        assertEquals(1L, h.component.state()["encoderUnrecoveredErrorCount"])
    }

    @Test
    fun codecV2EncoderOverflowInvalidParamsHolesAndSnapshotMigration() {
        val h = Harness(profile = SocJpegFactory.CODEC_PROFILE, delayTicks = 8L)
        val enc = assertNotNull(h.encoderMmio)
        h.irqEvents.clear()

        // Unmapped holes and sub-word widths refused
        for (badOff in listOf(0x004L, 0x008L, 0x020L, 0x090L, 0x104L, 0x128L, 0x134L, 0x002L)) {
            assertFailsWith<IllegalArgumentException> { enc.read(badOff, 4) }
            assertFailsWith<IllegalArgumentException> { enc.write(badOff, 4, 0L) }
        }
        assertFailsWith<IllegalArgumentException> { enc.read(0x000L, 2) }

        val width = 64
        val height = 48
        val srcUyvy = (JpegUyvyCodec.decodeToMemYc(encodeJpeg(makePatternImage(width, height)), 0L, 0, false, 0, 0, 0, 0) as JpegUyvyCodec.DecodeResult.Success).uyvyBytes
        val srcAddr = 0x8800_0000L
        val dstAddr = 0x8900_0000L
        h.dma.map(srcAddr, srcUyvy.size + 256)
        h.dma.map(dstAddr, 16384)
        h.dma.writeBytes(srcAddr, srcUyvy)

        // 1. Overflow when ENC_DST_JPEG_MAX is tiny (64 B): sets ENC_OUT_BYTES > max + bit4|bit17 + IRQ 135
        enc.write(0x028L, 4, 0xFFFD_FFEFL)
        enc.write(0x044L, 4, (height.toLong() shl 16) or width.toLong())
        enc.write(0x100L, 4, srcAddr)
        enc.write(0x10CL, 4, dstAddr)
        enc.write(0x110L, 4, 64L)
        enc.write(0x130L, 4, (width * 2).toLong())
        enc.write(0x000L, 4, 1L)
        h.tick += 8L
        enc.beforeInstruction(h.tick)
        assertTrue(enc.read(0x094L, 4) > 64L, "Overflow must report actual encoded size in ENC_OUT_BYTES for ISR check")
        assertEquals(0x0002_0010L, enc.read(0x024L, 4))
        assertEquals(listOf(135 to true), h.irqEvents)
        enc.write(0x024L, 4, 0x0002_0010L)
        assertEquals(listOf(135 to true, 135 to false), h.irqEvents)

        // 2. Snapshot round-trip mid-encode on codec-v2
        enc.write(0x110L, 4, 16384L)
        enc.write(0x000L, 4, 1L)
        val v2Snap = h.component.saveSnapshot()

        val hRestore = Harness(profile = SocJpegFactory.CODEC_PROFILE, delayTicks = 8L)
        hRestore.dma.map(srcAddr, srcUyvy.size + 256)
        hRestore.dma.map(dstAddr, 16384)
        hRestore.dma.writeBytes(srcAddr, srcUyvy)
        hRestore.component.restoreSnapshot(v2Snap)
        hRestore.irqEvents.clear()
        val enc2 = assertNotNull(hRestore.encoderMmio)
        assertEquals(h.tick + 8L, enc2.nextDeadline())
        hRestore.tick = h.tick + 8L
        enc2.beforeInstruction(hRestore.tick)
        assertEquals(0x10L, enc2.read(0x024L, 4))
        assertEquals(listOf(135 to true), hRestore.irqEvents)
        assertEquals(1L, hRestore.component.state()["encodeCount"])

        // 3. Upgrade v1 -> v2 succeeds; downgrade v2 -> v1 is refused
        val v1 = Harness(profile = SocJpegFactory.PROFILE, delayTicks = 8L)
        val v1Snap = v1.component.saveSnapshot()
        val upgraded = Harness(profile = SocJpegFactory.CODEC_PROFILE, delayTicks = 8L)
        upgraded.component.restoreSnapshot(v1Snap)
        assertEquals(0L, upgraded.component.state()["encodeCount"])
        assertFailsWith<IllegalArgumentException> {
            v1.component.restoreSnapshot(v2Snap)
        }
    }

    @Test
    fun codecV2EncoderSupportsDmaStopBit16PixelStrideAndQtblRateControl() {
        val h = Harness(profile = SocJpegFactory.CODEC_PROFILE, delayTicks = 4L)
        val enc = assertNotNull(h.encoderMmio)
        // ENC_DMA_CTRL (0x118) write raises INT_DMA_STOP_READY (bit 16) for firmware wait loop
        enc.write(0x118L, 4, 1L)
        assertEquals(0x0001_0000L, enc.read(0x024L, 4) and 0x0001_0000L)
        enc.write(0x024L, 4, 0x0001_0000L)
        assertEquals(0L, enc.read(0x024L, 4) and 0x0001_0000L)

        val width = 160
        val height = 120
        val srcUyvy = (JpegUyvyCodec.decodeToMemYc(encodeJpeg(makePatternImage(width, height)), 0L, 0, false, 0, 0, 0, 0) as JpegUyvyCodec.DecodeResult.Success).uyvyBytes
        val srcAddr = 0x8800_0000L
        val dstAddr = 0x8900_0000L
        h.dma.map(srcAddr, srcUyvy.size + 256)
        h.dma.map(dstAddr, 65536)
        h.dma.writeBytes(srcAddr, srcUyvy)

        fun encodeWithQtblByte(qVal: Int): Long {
            enc.write(0x024L, 4, 0xFFFF_FFFFL)
            enc.write(0x044L, 4, (height.toLong() shl 16) or width.toLong())
            enc.write(0x100L, 4, srcAddr)
            enc.write(0x10CL, 4, dstAddr)
            enc.write(0x110L, 4, 65536L)
            // Pixel-stride pitch (width = 160 -> 320 bytes/row)
            enc.write(0x130L, 4, width.toLong())
            enc.write(0x048L, 4, 0L)
            val packed = ((qVal and 0xFF).toLong()) * 0x0101_0101L
            enc.write(0x084L, 4, 0x100L)
            repeat(16) { enc.write(0x08CL, 4, packed) }
            enc.write(0x000L, 4, 1L)
            h.tick += 4L
            enc.beforeInstruction(h.tick)
            assertEquals(0x10L, enc.read(0x024L, 4) and 0x10L)
            return enc.read(0x094L, 4)
        }

        val highQualityBytes = encodeWithQtblByte(10)
        val lowQualityBytes = encodeWithQtblByte(120)
        assertTrue(highQualityBytes > lowQualityBytes, "Higher qtable values must shrink JPEG size ($highQualityBytes vs $lowQualityBytes)")
    }

    @Test
    fun codecV2EncoderAcceptsSuperFine102MpOutputBufferLimitAndRejectsAboveMax() {
        val h = Harness(profile = SocJpegFactory.CODEC_PROFILE, delayTicks = 4L)
        val enc = assertNotNull(h.encoderMmio)

        val width = 64
        val height = 48
        val srcUyvy = (JpegUyvyCodec.decodeToMemYc(encodeJpeg(makePatternImage(width, height)), 0L, 0, false, 0, 0, 0, 0) as JpegUyvyCodec.DecodeResult.Success).uyvyBytes
        val srcAddr = 0x8800_0000L
        val dstAddr = 0x6A01_4600L
        h.dma.map(srcAddr, srcUyvy.size + 256)
        h.dma.map(dstAddr, 65536)
        h.dma.writeBytes(srcAddr, srcUyvy)

        // BUG-V3-3-5: GFX100S 102MP IMAGE QUALITY = SUPER FINE programs dstMax = 76,317,696 B (72.78 MiB > old 64 MiB cap).
        val superFineDstMax = 76_317_696L
        enc.write(0x024L, 4, 0xFFFF_FFFFL)
        enc.write(0x044L, 4, (height.toLong() shl 16) or width.toLong())
        enc.write(0x100L, 4, srcAddr)
        enc.write(0x10CL, 4, dstAddr)
        enc.write(0x110L, 4, superFineDstMax)
        enc.write(0x130L, 4, width.toLong())
        enc.write(0x048L, 4, 1L)
        enc.write(0x000L, 4, 1L)
        h.tick += 4L
        enc.beforeInstruction(h.tick)
        assertEquals(0x10L, enc.read(0x024L, 4) and 0x10L, "SUPER FINE 72.78 MiB dstMax must succeed")
        assertEquals(1L, h.component.state()["encodeCount"])
        assertEquals(0L, h.component.state()["encoderErrorCount"])

        // Buffers above MAX_JPEG_BYTES (128 MiB) must still fail cleanly with INT_ERROR
        enc.write(0x024L, 4, 0xFFFF_FFFFL)
        enc.write(0x110L, 4, SocJpegFactory.MAX_JPEG_BYTES + 1L)
        enc.write(0x000L, 4, 1L)
        h.tick += 4L
        enc.beforeInstruction(h.tick)
        assertEquals(0x20000L, enc.read(0x024L, 4) and 0x20000L, "dstMax > MAX_JPEG_BYTES must assert INT_ERROR")
        assertEquals(1L, h.component.state()["encoderErrorCount"])
    }

    @Test
    fun encoderReadsMapped34BitUyvyInsteadOfItsLowAlias() {
        assertPhysicalSourceMatchesLowRam(0x1_b000_0f80L)
    }

    @Test
    fun encoderReadsUyvyAcrossFourGiBWithoutWrapping() {
        assertPhysicalSourceMatchesLowRam(0xffff_ff80L)
    }

    private fun assertPhysicalSourceMatchesLowRam(source: Long) {
        val width = 64
        val height = 48
        val image = makePatternImage(width, height)
        val decoded = JpegUyvyCodec.decodeToMemYc(encodeJpeg(image), 0L, 0, false, 0, 0, 0, 0)
        val uyvy = assertIs<JpegUyvyCodec.DecodeResult.Success>(decoded).uyvyBytes
        fun from(address: Long): ByteArray {
            val h = Harness(profile = SocJpegFactory.CODEC_PROFILE, delayTicks = 4L)
            val enc = assertNotNull(h.encoderMmio)
            val low = address and 0xffff_ffffL
            h.ports.register("soc-isp", "soc-isp.axi-high34", MmioDevice::class.java, object : MmioDevice {
                override fun read(offset: Long, size: Int) = if (offset == 0x40L) address ushr 32 else 0L
                override fun write(offset: Long, size: Int, value: Long) = Unit
            })
            h.dma.map(address, uyvy.size)
            h.dma.writeBytes(address, uyvy)
            if (low != address) {
                h.dma.map(low, uyvy.size)
                h.dma.writeBytes(low, ByteArray(uyvy.size) { 0x80.toByte() })
            }
            val output = 0x6a00_0000L
            h.dma.map(output, 65536)
            enc.write(0x024L, 4, 0xffff_ffffL)
            enc.write(0x044L, 4, (height.toLong() shl 16) or width.toLong())
            enc.write(0x100L, 4, low)
            enc.write(0x10cL, 4, output)
            enc.write(0x110L, 4, 65536L)
            enc.write(0x130L, 4, width.toLong())
            enc.write(0x048L, 4, 1L)
            enc.write(0x000L, 4, 1L)
            h.tick += 4L
            enc.beforeInstruction(h.tick)
            val length = enc.read(0x094L, 4).toInt()
            assertTrue(length > 0, "Expected a JPEG from mapped source 0x${address.toString(16)}")
            return h.dma.readBytes(output, length)
        }
        assertContentEquals(from(0x8800_0000L), from(source))
    }

    @Test
    fun codecV2EncoderRoutes34BitAxiHighDestinationToHighPhysicalRam() {
        val h = Harness(profile = SocJpegFactory.CODEC_PROFILE, delayTicks = 4L)
        val enc = assertNotNull(h.encoderMmio)
        // Register 0xFFF4A740/44 AXI high-34 port with dstHi=1 (PA 0x1_6A00_0F40, used by SUPER FINE)
        h.ports.register(
            "soc-isp",
            "soc-isp.axi-high34",
            MmioDevice::class.java,
            object : MmioDevice {
                override fun read(offset: Long, size: Int): Long = if (offset == 0x44L) 1L else 0L
                override fun write(offset: Long, size: Int, value: Long) = Unit
            },
        )

        val width = 64
        val height = 48
        val srcUyvy = (JpegUyvyCodec.decodeToMemYc(encodeJpeg(makePatternImage(width, height)), 0L, 0, false, 0, 0, 0, 0) as JpegUyvyCodec.DecodeResult.Success).uyvyBytes
        val srcAddr = 0x8800_0000L
        val lowDstAddr = 0x6A00_0F40L
        val highDstAddr = 0x1_6A00_0F40L
        h.dma.map(srcAddr, srcUyvy.size + 256)
        h.dma.map(lowDstAddr, 65536)
        h.dma.map(highDstAddr, 65536)
        h.dma.writeBytes(srcAddr, srcUyvy)

        enc.write(0x024L, 4, 0xFFFF_FFFFL)
        enc.write(0x044L, 4, (height.toLong() shl 16) or width.toLong())
        enc.write(0x100L, 4, srcAddr)
        enc.write(0x10CL, 4, lowDstAddr)
        enc.write(0x110L, 4, 76_317_696L)
        enc.write(0x130L, 4, width.toLong())
        enc.write(0x048L, 4, 1L)
        enc.write(0x000L, 4, 1L)
        h.tick += 4L
        enc.beforeInstruction(h.tick)

        assertEquals(0x10L, enc.read(0x024L, 4) and 0x10L, "34-bit AXI dstHi=1 encode must succeed")
        val outLen = enc.read(0x094L, 4).toInt()
        assertTrue(outLen > 128, "Expected valid JPEG output length, got $outLen")
        val highBytes = h.dma.readBytes(highDstAddr, outLen)
        assertEquals(0xFF.toByte(), highBytes[0], "JPEG SOI must be written to 34-bit PA 0x1_6A00_0F40")
        assertEquals(0xD8.toByte(), highBytes[1], "JPEG SOI must be written to 34-bit PA 0x1_6A00_0F40")
        val lowPrefix = h.dma.readBytes(lowDstAddr, 4)
        assertTrue(lowPrefix.all { it == 0.toByte() }, "Low 32-bit alias 0x0_6A00_0F40 must NOT be clobbered when high PA is mapped")
    }

    @Test
    fun zeroFilled32BitThumbnailBufferSynthesizesOpticalSensorAndMemYcBlackMarginsAvoidGreen() {
        // BUG-V3-8-4 Part B (1): Without CMOS sensor, Milbeaut black margin words (0x00000000,
        // 0x80800000, 0x00008080) in encodeFromMemYc must encode pure black (0,0,0), not BT.601 green.
        val blackBuf = ByteArray(16 * 16 * 2)
        for (i in 0 until blackBuf.size step 4) {
            when ((i / 4) % 3) {
                0 -> { /* 0x00000000 */ }
                1 -> { blackBuf[i + 2] = 0x80.toByte(); blackBuf[i + 3] = 0x80.toByte() }
                2 -> { blackBuf[i] = 0x80.toByte(); blackBuf[i + 1] = 0x80.toByte() }
            }
        }
        val blackEnc = JpegUyvyCodec.encodeFromMemYc(blackBuf, 16, 16, 32, 0, 65536L)
        assertTrue(blackEnc is JpegUyvyCodec.EncodeResult.Success)
        val blackImg = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(blackEnc.jpegBytes))
        val cRgb = blackImg.getRGB(8, 8)
        val cR = (cRgb ushr 16) and 0xFF
        val cG = (cRgb ushr 8) and 0xFF
        val cB = cRgb and 0xFF
        assertTrue(cR < 10 && cG < 10 && cB < 10, "Zero/Milbeaut black margin words must encode near-black, got ($cR,$cG,$cB)")

        // BUG-V3-8-4 Part B (2): With a live CMOS sensor registered, encoding an unwritten
        // all-zero 32-bit DRAM 160x120 EXIF thumbnail buffer (e.g. 0xbfefe000) must synthesize
        // from the optical CMOS sensor instead of producing a solid bright-green thumbnail.
        val h = Harness(profile = SocJpegFactory.CODEC_PROFILE, delayTicks = 4L)
        h.ports.register(
            "cmos",
            "cmos",
            SensorPort::class.java,
            object : SensorPort {
                override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                    val count = destination.remaining() / 2
                    val pixel = (2400 shl 4).toShort()
                    val lo = (pixel.toInt() and 0xFF).toByte()
                    val hi = ((pixel.toInt() ushr 8) and 0xFF).toByte()
                    repeat(count) {
                        destination.put(lo)
                        destination.put(hi)
                    }
                    return count * 2
                }
            },
        )
        val enc = assertNotNull(h.encoderMmio)
        val srcAddr = 0xbfef_e000L
        val dstAddr = 0x8900_0000L
        h.dma.map(srcAddr, 160 * 120 * 2) // unwritten all-zero DRAM!
        h.dma.map(dstAddr, 65536)
        enc.write(0x024L, 4, 0xFFFF_FFFFL)
        enc.write(0x044L, 4, (120L shl 16) or 160L)
        enc.write(0x100L, 4, srcAddr)
        enc.write(0x10CL, 4, dstAddr)
        enc.write(0x110L, 4, 65536L)
        enc.write(0x130L, 4, 160L)
        enc.write(0x048L, 4, 0L)
        enc.write(0x000L, 4, 1L)
        h.tick += 4L
        enc.beforeInstruction(h.tick)
        assertEquals(0x10L, enc.read(0x024L, 4) and 0x10L)
        val outLen = enc.read(0x094L, 4).toInt()
        assertTrue(outLen > 500, "Synthesized 160x120 thumbnail JPEG must be valid (got $outLen bytes)")
        val thumbImg = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(h.dma.readBytes(dstAddr, outLen)))
        assertEquals(160, thumbImg.width)
        assertEquals(120, thumbImg.height)
        val tRgb = thumbImg.getRGB(80, 60)
        val tR = (tRgb ushr 16) and 0xFF
        val tG = (tRgb ushr 8) and 0xFF
        val tB = tRgb and 0xFF
        assertTrue(tR > 80 && tG > 80 && tB > 80 && kotlin.math.abs(tG - tR) < 30,
            "Synthesized 160x120 EXIF thumbnail must be bright neutral optical scene, not solid green (got $tR,$tG,$tB)")
    }

    @Test
    fun stockRafPreviewUsesProducerPixelPitchBeyondTwiceTheEncodeWidth() {
        assertWidePreviewStride(stockRecc = true)
    }

    @Test
    fun legacyPaddedBytePitchStillReadsTheBottomOfThePreview() {
        assertWidePreviewStride(stockRecc = false)
    }

    private fun assertWidePreviewStride(stockRecc: Boolean) {
        val h = Harness(profile = SocJpegFactory.CODEC_PROFILE, delayTicks = 4L)
        val enc = assertNotNull(h.encoderMmio)
        val width = 4000
        val height = 32
        val programmedPitch = 11664
        val bytePitch = if (stockRecc) programmedPitch * 2 else programmedPitch
        val source = ByteArray((height - 1) * bytePitch + width * 2)
        for (row in 0 until height) {
            val luma = if (row < height / 2) 40 else 210
            for (x in 0 until width step 2) {
                val offset = row * bytePitch + x * 2
                source[offset] = luma.toByte()
                source[offset + 1] = luma.toByte()
                source[offset + 2] = 128.toByte()
                source[offset + 3] = 128.toByte()
            }
        }
        val src = 0x4000_0000L
        val dst = 0x5000_0000L
        h.dma.map(src, source.size)
        h.dma.map(dst, 64 * 1024)
        h.dma.writeBytes(src, source)
        val size = (height.toLong() shl 16) or width.toLong()
        enc.write(0x044L, 4, size)
        if (stockRecc) {
            enc.write(0x00cL, 4, 0x000a_0003L)
            enc.write(0x040L, 4, 1L)
            enc.write(0x11cL, 4, 1L)
        } else {
            enc.write(0x00cL, 4, 0x000a_0000L)
            enc.write(0x12cL, 4, size)
        }
        enc.write(0x100L, 4, src)
        enc.write(0x10cL, 4, dst)
        enc.write(0x110L, 4, 65536L)
        enc.write(0x130L, 4, programmedPitch.toLong())
        enc.write(0x000L, 4, 1L)
        h.tick += 4
        enc.beforeInstruction(h.tick)

        assertEquals(0x10L, enc.read(0x024L, 4))
        val decoded = ImageIO.read(h.dma.readBytes(dst, enc.read(0x094L, 4).toInt()).inputStream())
        for (x in listOf(0, width / 2, width - 1)) {
            assertTrue(Color(decoded.getRGB(x, 0)).green < 70)
            assertTrue(Color(decoded.getRGB(x, height - 1)).green > 180, "lost lower source row at column $x")
        }
        assertEquals(source.size.toLong(), h.component.state()["lastSourceBytes"])
    }

    @Test
    fun codecV2Accepts102MpStridedSourceBufferAbove256MiBForRawConversion() {
        // Real GFX100S 102MP RAW conversion programs 11648x8736 with pixel pitch 15840 (31680 B/row):
        // srcBytesNeeded = 8735 * 31680 + 11648 * 2 = 276,748,096 B (~263.9 MiB > legacy 256 MiB ceiling).
        val rawConvSpanBytes = (8736L - 1L) * 31_680L + 11_648L * 2L
        assertEquals(276_748_096L, rawConvSpanBytes)
        assertTrue(
            rawConvSpanBytes <= SocJpegFactory.MAX_SOURCE_YC_BYTES,
            "102MP RAW conversion UYVY span must fit within 512 MiB ceiling",
        )
        // Verify strided encoder round-trip when linePitch exceeds width*2 (matching RECC_S/RESZ 31680 B stride)
        val h = Harness(profile = SocJpegFactory.CODEC_PROFILE, delayTicks = 4L)
        val enc = assertNotNull(h.encoderMmio)
        val width = 128
        val height = 96
        val pixelPitch = 192 // 384 B/row > 256 B/row
        val bytePitch = pixelPitch * 2
        val packed = (JpegUyvyCodec.decodeToMemYc(encodeJpeg(makePatternImage(width, height)), 0L, 0, false, 0, 0, 0, 0) as JpegUyvyCodec.DecodeResult.Success).uyvyBytes
        val strided = ByteArray(height * bytePitch)
        for (y in 0 until height) {
            System.arraycopy(packed, y * width * 2, strided, y * bytePitch, width * 2)
        }
        val srcAddr = 0x4000_0000L
        val dstAddr = 0x5000_0000L
        h.dma.map(srcAddr, strided.size + 4096)
        h.dma.map(dstAddr, 65536)
        h.dma.writeBytes(srcAddr, strided)

        enc.write(0x024L, 4, 0xFFFF_FFFFL)
        enc.write(0x044L, 4, (height.toLong() shl 16) or width.toLong())
        enc.write(0x100L, 4, srcAddr)
        enc.write(0x10CL, 4, dstAddr)
        enc.write(0x110L, 4, 65536L)
        enc.write(0x130L, 4, pixelPitch.toLong())
        enc.write(0x048L, 4, 16L)
        enc.write(0x000L, 4, 1L)
        h.tick += 4L
        enc.beforeInstruction(h.tick)
        assertEquals(0x10L, enc.read(0x024L, 4) and 0x10L)
        assertEquals(0L, h.component.state()["encoderErrorCount"])
        val outSize = enc.read(0x094L, 4).toInt()
        assertTrue(outSize > 512)
        val decoded = JpegUyvyCodec.decodeToMemYc(h.dma.readBytes(dstAddr, outSize), 0L, 0, false, 0, 0, 0, 0)
        assertTrue(decoded is JpegUyvyCodec.DecodeResult.Success && decoded.width == width && decoded.height == height)
    }
}
