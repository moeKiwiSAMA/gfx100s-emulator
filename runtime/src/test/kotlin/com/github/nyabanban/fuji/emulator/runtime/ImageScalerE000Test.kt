package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class ImageScalerE000Test {
    private val catalog = AddressCatalog.bundled()
    private val context = object : DiscoveryContext {
        override fun core() = 0
        override fun pc() = 0x5afe0L
        override fun lr() = 0x11fccd0L
        override fun tick() = 750_000_000L
    }

    private fun bus(scaler: String? = ImageScalerE000.PROFILE, mode: String = "discovery"): DiscoveryBus {
        val discovery = linkedMapOf<String, Any>()
        if (scaler != null) discovery[ImageScalerE000.KEY] = scaler
        val config = if (discovery.isEmpty()) emptyMap() else mapOf("discovery" to discovery)
        return checkNotNull(CatalogRuntime(catalog, mode, Path.of("."), config).createBus(context))
    }

    @Test
    fun resetIdleBitsRetentionAndSnapshotRoundTrip() {
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(catalog, "strict", Path.of("."), mapOf("discovery" to mapOf(ImageScalerE000.KEY to ImageScalerE000.PROFILE)))
        }
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(catalog, "strict", Path.of("."), mapOf("discovery" to mapOf(ImageScalerE000.KEY to ImageScalerE000.IRQ132_PROFILE)))
        }

        val b = bus()
        var seq = 1L
        // Initial read of E100/E104 returns 0; E10C sees both idle bits (0x01000100)
        assertEquals(0L, b.access(seq++, 0xfff7e100L, 4, false))
        assertEquals(0L, b.access(seq++, 0xfff7e104L, 4, false))
        assertEquals(0x01000100L, b.access(seq++, 0xfff7e10cL, 4, false))

        // Enabling E100 bit 28 clears E10C bit 8; enabling E104 bits 31:28 clears E10C bit 24
        b.access(seq++, 0xfff7e100L, 4, true, 0x10000011L)
        assertEquals(0x01000000L, b.access(seq++, 0xfff7e10cL, 4, false))
        b.access(seq++, 0xfff7e104L, 4, true, 0x30000022L)
        assertEquals(0L, b.access(seq++, 0xfff7e10cL, 4, false))

        // Clearing both restores 0x01000100
        b.access(seq++, 0xfff7e100L, 4, true, 0x00000011L)
        b.access(seq++, 0xfff7e104L, 4, true, 0x00000022L)
        assertEquals(0x01000100L, b.access(seq++, 0xfff7e10cL, 4, false))

        // Reset at E000: write 0, read asserts bit 0 == 1
        b.access(seq++, 0xfff7e000L, 4, true, 0L)
        assertEquals(1L, b.access(seq++, 0xfff7e000L, 4, false))

        // Retention across config words and tail E118
        b.access(seq++, 0xfff7e0c0L, 4, true, 0x2e55a000L)
        b.access(seq++, 0xfff7e0d4L, 4, true, 0x2c482000L)
        b.access(seq++, 0xfff7e118L, 4, true, 0x0000beefL)
        assertEquals(0x2e55a000L, b.access(seq++, 0xfff7e0c0L, 4, false))
        assertEquals(0x2c482000L, b.access(seq++, 0xfff7e0d4L, 4, false))
        assertEquals(0x0000beefL, b.access(seq++, 0xfff7e118L, 4, false))

        // Snapshot round-trip
        val saved = b.saveSnapshot()
        val restored = bus()
        restored.prepareSnapshotRestore(saved, allowPromotedRegions = false)()
        assertEquals(0x2e55a000L, restored.access(seq++, 0xfff7e0c0L, 4, false))
        assertEquals(0x0000beefL, restored.access(seq++, 0xfff7e118L, 4, false))
        assertEquals(0x01000100L, restored.access(seq++, 0xfff7e10cL, 4, false))

        // Promotion from snapshot without ImageScalerE000
        val emptySnap = bus(scaler = null).saveSnapshot()
        val promoted = bus(scaler = ImageScalerE000.IRQ132_PROFILE)
        assertFailsWith<IllegalArgumentException> {
            promoted.prepareSnapshotRestore(emptySnap, allowPromotedRegions = false)
        }
        promoted.prepareSnapshotRestore(emptySnap, allowPromotedRegions = true)()
        assertEquals(0x01000100L, promoted.access(1L, 0xfff7e10cL, 4, false))
    }

    private class MockDmaMemory(private val maxTransfer: Int = Int.MAX_VALUE) : DmaMemory {
        private val regions = LinkedHashMap<Long, ByteArray>()
        fun map(base: Long, size: Int) { regions[base] = ByteArray(size) }
        fun bytes(base: Long): ByteArray = regions.getValue(base)

        private fun locate(address: Long, size: Long): Pair<Long, ByteArray> {
            for ((base, arr) in regions) {
                if (address >= base && address + size <= base + arr.size.toLong()) return base to arr
            }
            throw IllegalArgumentException("Unmapped DMA range 0x${address.toString(16)} size=$size")
        }

        override fun check(address: Long, size: Long, write: Boolean) { locate(address, size) }
        override fun read(address: Long, destination: ByteBuffer) {
            val len = destination.remaining()
            require(len <= maxTransfer) { "Unbounded read: $len" }
            val (base, arr) = locate(address, len.toLong())
            destination.put(arr, (address - base).toInt(), len)
        }
        override fun write(address: Long, source: ByteBuffer) {
            val len = source.remaining()
            require(len <= maxTransfer) { "Unbounded write: $len" }
            val (base, arr) = locate(address, len.toLong())
            source.get(arr, (address - base).toInt(), len)
        }
    }

    @Test
    fun stillCropReadsAcrossFourGiBIntoHighPhysicalRam() {
        assertPhysicalStillCrop(0xffff_fff4L, 0x1_1000_0ffcL)
    }

    @Test
    fun stillCropWritesAcrossFourGiBFromHighPhysicalRam() {
        assertPhysicalStillCrop(0x1_3000_0ff4L, 0xffff_fffcL)
    }

    @Test
    fun largePhysicalStillDownsampleUsesBoundedRowsAndPreservesPadding() {
        val src = 0x1_1000_0000L
        val dst = 0x1_9000_0000L
        val width = 2048
        val height = 2048
        val sourceStride = width * 4
        val outputStride = width * 2 + 8
        val dma = MockDmaMemory(maxTransfer = sourceStride)
        dma.map(src, sourceStride * height)
        dma.map(dst, outputStride * height)
        val source = dma.bytes(src)
        val output = dma.bytes(dst)
        output.fill(0x55)
        for (i in source.indices) source[i] = (32 + i % 181).toByte()

        val scaler = ImageScalerE000(ImageScalerE000.IRQ132_PROFILE)
        fun write(offset: Int, value: Long) = scaler.access(0xfff7e000L + offset, true, value)
        write(0x08, 0x100)
        write(0x0c, (height - 1L) shl 16 or (width * 2 - 1L))
        write(0x14, (height - 1L) shl 16 or (width * 2 - 1L))
        write(0x18, (height - 1L) shl 16 or (width - 1L))
        write(0xc0, src ushr 32)
        write(0xc4, src and 0xffff_ffffL)
        write(0xd0, sourceStride.toLong())
        write(0xd4, dst ushr 32)
        write(0xd8, dst and 0xffff_ffffL)
        write(0xe4, outputStride.toLong())
        write(0x04, 1)
        scaler.beforeInstruction(ImageScalerE000.COMPLETION_DELAY_TICKS, dma) {}
        for (y in 0 until height) {
            val start = y * outputStride
            val srcRow = y * sourceStride
            assertEquals(source[srcRow], output[start])
            assertEquals(source[srcRow + sourceStride - 3], output[start + width * 2 - 3])
            assertEquals(0x55, output[start + width * 2].toInt())
        }
    }

    @Test
    fun largeStillThumbnailStreamsRowsWithoutFullSourceRead() {
        for ((src, dst) in listOf(0x1_7300_0000L to 0x2c48_2000L, 0xf300_0000L to 0x2c48_2000L)) {
            val srcW = 4096
            val srcH = 2160
            val dstW = 160
            val dstH = 120
            val srcStride = srcW * 2
            val dstStride = dstW * 2
            val dma = MockDmaMemory(maxTransfer = srcStride)
            dma.map(src, srcStride * srcH)
            dma.map(dst, dstStride * dstH)
            val source = dma.bytes(src)
            for (y in 0 until srcH) {
                val yVal = (16 + (y * 200) / (srcH - 1)).toByte()
                val rowOff = y * srcStride
                source[rowOff] = yVal
                source[rowOff + 1] = yVal
                source[rowOff + 2] = 0x80.toByte()
                source[rowOff + 3] = 0x80.toByte()
                source[rowOff + srcStride - 4] = yVal
                source[rowOff + srcStride - 3] = yVal
                source[rowOff + srcStride - 2] = 0x80.toByte()
                source[rowOff + srcStride - 1] = 0x80.toByte()
            }

            val scaler = ImageScalerE000(ImageScalerE000.IRQ132_PROFILE)
            fun write(offset: Int, value: Long) = scaler.access(0xfff7e000L + offset, true, value)
            write(0x08, 0x100)
            write(0x0c, ((srcH - 1L) shl 16) or (srcW - 1L))
            write(0x14, ((srcH - 1L) shl 16) or (srcW - 1L))
            write(0x18, ((dstH - 1L) shl 16) or (dstW - 1L))
            write(0xc0, src ushr 32)
            write(0xc4, src and 0xffff_ffffL)
            write(0xd0, srcStride.toLong())
            write(0xd4, dst ushr 32)
            write(0xd8, dst and 0xffff_ffffL)
            write(0xe4, dstStride.toLong())
            write(0x04, 1)
            var completed = false
            scaler.beforeInstruction(ImageScalerE000.COMPLETION_DELAY_TICKS, dma) { completed = true }
            assertTrue(completed)
            val output = dma.bytes(dst)
            assertEquals(16, output[0].toInt() and 0xff)
            assertEquals(216, output[(dstH - 1) * dstStride].toInt() and 0xff)
        }
    }

    private fun assertPhysicalStillCrop(source: Long, destination: Long) {
        val dma = MockDmaMemory()
        val sourceStride = 24
        val destinationStride = 12
        dma.map(source, 3 * sourceStride + 16)
        dma.map(destination, 3 * destinationStride + 8 + 4)
        val input = dma.bytes(source)
        for (i in input.indices) input[i] = (16 + (i * 7) % 223).toByte()
        val output = dma.bytes(destination)
        output.fill(0x55)
        val expected = output.copyOf()
        for (y in 0 until 4) {
            input.copyInto(expected, y * destinationStride, y * sourceStride + 4, y * sourceStride + 12)
        }

        val scaler = ImageScalerE000(ImageScalerE000.IRQ132_PROFILE)
        fun write(offset: Int, value: Long) = scaler.access(0xfff7e000L + offset, true, value)
        write(0x08, 0x100)
        write(0x0c, 0x0003_0007)
        write(0x10, 2)
        write(0x14, 0x0003_0003)
        write(0x18, 0x0003_0003)
        write(0xc0, source ushr 32)
        write(0xc4, source and 0xffff_ffffL)
        write(0xd0, sourceStride.toLong())
        write(0xd4, destination ushr 32)
        write(0xd8, destination and 0xffff_ffffL)
        write(0xe4, destinationStride.toLong())
        write(0x04, 1)
        var completed = false
        scaler.beforeInstruction(ImageScalerE000.COMPLETION_DELAY_TICKS, dma) { completed = true }
        assertTrue(completed)
        assertContentEquals(expected, output)
    }

    @Test
    fun dciPlaybackCropsPaddedTileRowsAndTransfersLargeFramesWithinTheNativeLimit() {
        val src = 0x3400_0000L
        val dst = 0x3800_0000L
        val width = 4095
        val height = 2159
        val tileStride = 16448
        val dstStride = 8192
        val dma = MockDmaMemory(maxTransfer = 16 * 1024 * 1024)
        // Last source tile row ends immediately after its picture data, without its usual padding.
        dma.map(src, 1079 * tileStride + 16384)
        dma.map(dst, height * dstStride + 8)
        dma.bytes(dst).fill(0x55)
        val tiles = ByteBuffer.wrap(dma.bytes(src)).order(ByteOrder.LITTLE_ENDIAN)
        fun luma(x: Int, y: Int) = 10 + ((37 * x + 3 * y) and 127)
        val rows = listOf(0, 1, 1023, 1024, height - 1)
        val columns = listOf(0, 1, width - 2, width - 1)
        for (y in rows) {
            for (x in columns) {
                val tileX = (x + 1) / 2
                val tileY = (y + 1) / 2
                // Each cropped output pair may reference adjacent source tiles.
                for (tx in maxOf(0, tileX - 1)..minOf(2047, tileX + 1)) {
                    for (field in 0..1) {
                        val sy = tileY * 2 + field
                        val chroma = if (field == 0) 64 else 151
                        tiles.putInt(tileY * tileStride + tx * 8 + field * 4,
                            (luma(tx * 2, sy) shl 2) or (chroma shl 12) or (luma(tx * 2 + 1, sy) shl 22))
                    }
                }
            }
        }
        val scaler = ImageScalerE000(ImageScalerE000.IRQ132_PROFILE)
        fun set(offset: Int, value: Long) { scaler.access(0xfff7e000L + offset, true, value) }
        set(0x08, 0x103)
        set(0x0c, (2159L shl 16) or 4095L)
        set(0x10, (1L shl 16) or 1L)
        val cropSize = ((height - 1L) shl 16) or (width - 1L)
        set(0x14, cropSize)
        set(0x18, cropSize)
        set(0xc4, src)
        set(0xd0, tileStride.toLong())
        set(0xd8, dst)
        set(0xe4, dstStride.toLong())
        set(0x04, 1)
        var completed = false
        scaler.beforeInstruction(ImageScalerE000.COMPLETION_DELAY_TICKS, dma) { completed = true }
        assertTrue(completed)
        val result = dma.bytes(dst)
        for (y in rows) for (x in columns) {
            assertEquals(Triple(luma(x + 1, y + 1), 64, 151), readUyvyPixel(result, x, y, dstStride))
        }
        assertEquals(result[height * dstStride - 4], result[height * dstStride - 3])
        assertTrue(result.takeLast(8).all { it == 0x55.toByte() })
    }

    private fun fillUyvyQuadrants(buf: ByteArray, width: Int, height: Int, stride: Int) {
        for (y in 0 until height) {
            val rowOff = y * stride
            val top = y < height / 2
            for (x in 0 until width step 2) {
                val left = x < width / 2
                val (yVal, uVal, vVal) = when {
                    top && left -> Triple(40, 200, 100)
                    top && !left -> Triple(110, 80, 210)
                    !top && left -> Triple(180, 140, 60)
                    else -> Triple(240, 110, 160)
                }
                val p = rowOff + x * 2
                buf[p] = yVal.toByte()
                buf[p + 1] = yVal.toByte()
                buf[p + 2] = uVal.toByte()
                buf[p + 3] = vVal.toByte()
            }
        }
    }

    private fun readUyvyPixel(buf: ByteArray, x: Int, y: Int, stride: Int): Triple<Int, Int, Int> {
        val off = y * stride + (x and 1.inv()) * 2
        val luma = buf[off + (x and 1)].toInt() and 0xff
        val u = buf[off + 2].toInt() and 0xff
        val v = buf[off + 3].toInt() and 0xff
        return Triple(luma, u, v)
    }

    @Test
    fun irq132CompletionScalesUyvy640x480To1024x768AndClearsOnE10cWrite() {
        var currentTick = 750_100_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val srcAddr = 0x2e55_a000L
        val dstAddr = 0x2c48_2000L
        val srcW = 640
        val srcH = 480
        val srcStride = srcW * 2
        val dstW = 1024
        val dstH = 768
        val dstStride = dstW * 2
        dma.map(srcAddr, srcStride * srcH)
        dma.map(dstAddr, dstStride * dstH)
        fillUyvyQuadrants(dma.bytes(srcAddr), srcW, srcH, srcStride)

        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val dmaCtx = object : DiscoveryContext {
            override fun core() = 0
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fcce0L
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
        }
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerE000.KEY to ImageScalerE000.IRQ132_PROFILE)))
                .createBus(dmaCtx),
        )

        var seq = 1L
        // Kicking E004 without E0C0..E0F8 setup must not arm IRQ 132
        b.access(seq++, 0xfff7e004L, 4, true, 1L)
        assertNull(b.nextDeadline())
        b.beforeInstruction(currentTick + 500L)
        assertTrue(irqLog.isEmpty())

        // Program 640x480 -> 1024x768 packed UYVY422 RESZ scaling
        b.access(seq++, 0xfff7e008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7e00cL, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7e010L, 4, true, 0L)
        b.access(seq++, 0xfff7e014L, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7e018L, 4, true, ((dstH - 1).toLong() shl 16) or (dstW - 1).toLong())
        b.access(seq++, 0xfff7e0c4L, 4, true, srcAddr)
        b.access(seq++, 0xfff7e0d0L, 4, true, srcStride.toLong())
        b.access(seq++, 0xfff7e0d8L, 4, true, dstAddr)
        b.access(seq++, 0xfff7e0e4L, 4, true, dstStride.toLong())
        b.access(seq++, 0xfff7e004L, 4, true, 1L)

        val due = currentTick + ImageScalerE000.COMPLETION_DELAY_TICKS
        assertEquals(due, b.nextDeadline())
        b.beforeInstruction(due - 1L)
        assertTrue(irqLog.isEmpty())
        assertTrue(dma.bytes(dstAddr).all { it == 0.toByte() })

        b.beforeInstruction(due)
        assertEquals(listOf(132 to true), irqLog)
        assertNull(b.nextDeadline())
        assertEquals(0x01000101L, b.access(seq++, 0xfff7e10cL, 4, false))

        // Verify all 4 scaled quadrants
        val out = dma.bytes(dstAddr)
        assertEquals(Triple(40, 200, 100), readUyvyPixel(out, 160, 120, dstStride))
        assertEquals(Triple(110, 80, 210), readUyvyPixel(out, 860, 120, dstStride))
        assertEquals(Triple(180, 140, 60), readUyvyPixel(out, 160, 640, dstStride))
        assertEquals(Triple(240, 110, 160), readUyvyPixel(out, 860, 640, dstStride))

        // W1C ack clears GIC line 132
        b.access(seq++, 0xfff7e10cL, 4, true, 1L)
        assertEquals(listOf(132 to true, 132 to false), irqLog)
        assertEquals(0x01000100L, b.access(seq++, 0xfff7e10cL, 4, false))
    }

    @Test
    fun playDspFa00StrictRefusalIrq145CompletionAckAndSnapshotRoundTrip() {
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(catalog, "strict", Path.of("."), mapOf("discovery" to mapOf("playDspFa00" to "fa000000-ready-idle-v1")))
        }
        var currentTick = 762_000_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val dspCtx = object : DiscoveryContext {
            override fun core() = 0
            override fun pc() = 0x5af98L
            override fun lr() = 0x11e3024L
            override fun tick() = currentTick
            override fun interruptSink() = sink
        }
        fun dspBus(): DiscoveryBus = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf("playDspFa00" to "fa000000-ready-idle-v1")))
                .createBus(dspCtx),
        )

        val b = dspBus()
        var seq = 1L
        // Initial status bit 16 ready, bit 0 idle; companion windows zero-initialized
        assertEquals(0x00010000L, b.access(seq++, 0xfa000000L, 4, false))
        assertEquals(0L, b.access(seq++, 0xfa400518L, 4, false))
        assertEquals(0L, b.access(seq++, 0xfff4a760L, 4, false))
        assertEquals(0L, b.access(seq++, 0xfff4aa10L, 4, false))

        // Shared SRAM byte/word writes retained across 0xFA000040..0xFA000FFF
        b.access(seq++, 0xfa000040L, 4, true, 0x406f8000L)
        b.access(seq++, 0xfa000500L, 1, true, 0x5aL)
        assertEquals(0x406f8000L, b.access(seq++, 0xfa000040L, 4, false))
        assertEquals(0x5aL, b.access(seq++, 0xfa000500L, 1, false))

        // Writing bit 0 on 0xFA000000 arms IRQ 145 completion after delay and keeps ready/idle readback
        b.access(seq++, 0xfa000000L, 4, true, 0x00010001L)
        assertEquals(0x00010000L, b.access(seq++, 0xfa000000L, 4, false))
        val due = currentTick + PLAY_DSP_FA00_DELAY_TICKS
        assertEquals(due, b.nextDeadline())

        // Snapshot round-trip preserves pending IRQ 145 deadline
        val pendingSnap = b.saveSnapshot()
        val restored = dspBus()
        restored.prepareSnapshotRestore(pendingSnap, allowPromotedRegions = false)()
        assertEquals(due, restored.nextDeadline())

        restored.beforeInstruction(due - 1L)
        assertTrue(irqLog.isEmpty())
        restored.beforeInstruction(due)
        assertEquals(listOf(145 to true), irqLog)
        assertNull(restored.nextDeadline())

        // ISR writes 0xFFF4AA10 bit 3 -> deasserts level IRQ 145
        restored.access(seq++, 0xfff4aa10L, 4, true, 0x8L)
        assertEquals(listOf(145 to true, 145 to false), irqLog)
    }

    @Test
    fun scalerE000SupportsFilterTapPaddingAndFastOneToOneCopy() {
        val currentTick = 800_000_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val srcAddr = 0x59fe_6000L
        val dstAddr = 0x5a10_0000L
        val stride = 64 * 2
        val height = 32
        dma.map(srcAddr, stride * (height + 4))
        dma.map(dstAddr, stride * height)
        fillUyvyQuadrants(dma.bytes(srcAddr), 64, height, stride)

        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val dmaCtx = object : DiscoveryContext {
            override fun core() = 0
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fcce0L
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
        }
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerE000.KEY to ImageScalerE000.IRQ132_PROFILE)))
                .createBus(dmaCtx),
        )
        var seq = 1L
        // 1:1 copy fast path (64x32 -> 64x32, identical stride)
        b.access(seq++, 0xfff7e008L, 4, true, 0L)
        b.access(seq++, 0xfff7e00cL, 4, true, ((height - 1).toLong() shl 16) or 63L)
        b.access(seq++, 0xfff7e010L, 4, true, 0L)
        b.access(seq++, 0xfff7e014L, 4, true, ((height - 1).toLong() shl 16) or 63L)
        b.access(seq++, 0xfff7e018L, 4, true, ((height - 1).toLong() shl 16) or 63L)
        b.access(seq++, 0xfff7e0c4L, 4, true, srcAddr)
        b.access(seq++, 0xfff7e0d0L, 4, true, stride.toLong())
        b.access(seq++, 0xfff7e0d8L, 4, true, dstAddr)
        b.access(seq++, 0xfff7e0e4L, 4, true, stride.toLong())
        b.access(seq++, 0xfff7e004L, 4, true, 1L)
        b.beforeInstruction(currentTick + ImageScalerE000.COMPLETION_DELAY_TICKS)
        assertContentEquals(dma.bytes(srcAddr).copyOf(stride * height), dma.bytes(dstAddr))
        b.access(seq++, 0xfff7e10cL, 4, true, 1L)

        // Filter-tap padded source width (72 > stride/2=64) scaling down to 48x24
        val dst2 = 0x5a20_0000L
        dma.map(dst2, 64 * 2 * 24)
        b.access(seq++, 0xfff7e008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7e00cL, 4, true, ((height + 3).toLong() shl 16) or 71L)
        b.access(seq++, 0xfff7e014L, 4, true, ((height + 3).toLong() shl 16) or 71L)
        b.access(seq++, 0xfff7e018L, 4, true, (23L shl 16) or 47L)
        b.access(seq++, 0xfff7e0d8L, 4, true, dst2)
        b.access(seq++, 0xfff7e004L, 4, true, 1L)
        b.beforeInstruction(currentTick + 2 * ImageScalerE000.COMPLETION_DELAY_TICKS)
        assertEquals(Triple(40, 200, 100), readUyvyPixel(dma.bytes(dst2), 4, 4, stride))
        assertEquals(Triple(240, 110, 160), readUyvyPixel(dma.bytes(dst2), 40, 20, stride))
    }

    @Test
    fun scalerE000Completes34BitAxiMovieRecordingFormatAndStillRefusesUnsupported32BitFormat() {
        val currentTick = 850_000_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val dmaCtx = object : DiscoveryContext {
            override fun core() = 0
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fcd94L
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
        }
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerE000.KEY to ImageScalerE000.IRQ132_PROFILE)))
                .createBus(dmaCtx),
        )
        var seq = 1L
        val src = 0x1_a000_0000L
        val dst = 0x1_a72c_0000L
        dma.map(src, 6144 * 1080)
        fillUyvyQuadrants(dma.bytes(src), 1920, 1080, 6144)
        // Asymmetric 2x2 patch distinguishes all four luma lanes and both chroma averages (YYUV422 [Y0, Y1, U, V]).
        byteArrayOf(17, 31, 47, 83).copyInto(dma.bytes(src), 0)
        byteArrayOf(207.toByte(), 233.toByte(), 93, 191.toByte()).copyInto(dma.bytes(src), 6144)
        dma.map(dst, 8192 * 540)
        dma.bytes(dst).fill(0x55)
        // Live Movie 1080p RESZ format (E008=0x131: srcFmt=1, dstFmt=3) targeting 34-bit AXI high-DDR
        b.access(seq++, 0xfff7e008L, 4, true, 0x131L)
        b.access(seq++, 0xfff7e00cL, 4, true, (1079L shl 16) or 1919L)
        b.access(seq++, 0xfff7e010L, 4, true, 0L)
        b.access(seq++, 0xfff7e014L, 4, true, (1079L shl 16) or 1919L)
        b.access(seq++, 0xfff7e018L, 4, true, (1079L shl 16) or 1919L)
        b.access(seq++, 0xfff7e0c0L, 4, true, 1L)
        b.access(seq++, 0xfff7e0c4L, 4, true, 0xa000_0000L)
        b.access(seq++, 0xfff7e0d0L, 4, true, 6144L)
        b.access(seq++, 0xfff7e0d4L, 4, true, 1L)
        b.access(seq++, 0xfff7e0d8L, 4, true, 0xa72c_0000L)
        b.access(seq++, 0xfff7e0e4L, 4, true, 8192L)
        b.access(seq++, 0xfff7e004L, 4, true, 1L)

        b.beforeInstruction(currentTick + ImageScalerE000.COMPLETION_DELAY_TICKS)
        val tiles = ByteBuffer.wrap(dma.bytes(dst)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals((17 shl 2) or (70 shl 12) or (31 shl 22), tiles.getInt(0))
        assertEquals((207 shl 2) or (137 shl 12) or (233 shl 22), tiles.getInt(4))
        assertEquals((240 shl 2) or (110 shl 12) or (240 shl 22), tiles.getInt(539 * 8192 + 959 * 8))
        assertEquals(0x55, dma.bytes(dst)[7680].toInt(), "tile row padding")
        assertEquals(listOf(132 to true), irqLog)
        assertEquals(0x01000101L, b.access(seq++, 0xfff7e10cL, 4, false))
        b.access(seq++, 0xfff7e10cL, 4, true, 1L)
        assertEquals(listOf(132 to true, 132 to false), irqLog)

        // Movie playback submits the inverse path from low DDR. Expand the asymmetric
        // tile to an odd size and check both the duplicated last pixel and untouched row padding.
        val playbackSource = 0x3937_0000L
        val playbackTarget = 0x2c4b_2000L
        dma.map(playbackSource, 16)
        dma.bytes(dst).copyInto(dma.bytes(playbackSource), 0, 0, 8)
        dma.map(playbackTarget, 36)
        dma.bytes(playbackTarget).fill(0x55)
        b.access(seq++, 0xfff7e008L, 4, true, 0x103L)
        b.access(seq++, 0xfff7e00cL, 4, true, 0x00010001L)
        b.access(seq++, 0xfff7e014L, 4, true, 0x00010001L)
        b.access(seq++, 0xfff7e018L, 4, true, 0x00020002L)
        b.access(seq++, 0xfff7e0c0L, 4, true, 0L)
        b.access(seq++, 0xfff7e0c4L, 4, true, playbackSource)
        b.access(seq++, 0xfff7e0d0L, 4, true, 16L)
        b.access(seq++, 0xfff7e0d4L, 4, true, 0L)
        b.access(seq++, 0xfff7e0d8L, 4, true, playbackTarget)
        b.access(seq++, 0xfff7e0e4L, 4, true, 12L)
        b.access(seq++, 0xfff7e004L, 4, true, 1L)
        b.beforeInstruction(currentTick + 2 * ImageScalerE000.COMPLETION_DELAY_TICKS)
        val previewRows = dma.bytes(playbackTarget).map { it.toInt() and 255 }.chunked(12)
        assertEquals(listOf(17, 17, 70, 137, 31, 31, 70, 137, 85, 85, 85, 85), previewRows[0])
        assertEquals(previewRows[0], previewRows[1])
        assertEquals(listOf(207, 207, 70, 137, 233, 233, 70, 137, 85, 85, 85, 85), previewRows[2])
        b.access(seq++, 0xfff7e10cL, 4, true, 1L)
        b.access(seq++, 0xfff7e008L, 4, true, 0x131L)

        // 32-bit guest DDR addresses still refuse unsupported non-0 pixel formats
        b.access(seq++, 0xfff7e0c0L, 4, true, 0L)
        b.access(seq++, 0xfff7e0c4L, 4, true, 0x2e55_a000L)
        b.access(seq++, 0xfff7e0d4L, 4, true, 0L)
        b.access(seq++, 0xfff7e0d8L, 4, true, 0x2c48_2000L)
        b.access(seq++, 0xfff7e004L, 4, true, 1L)
        assertFailsWith<IllegalArgumentException> {
            b.beforeInstruction(currentTick + 2 * ImageScalerE000.COMPLETION_DELAY_TICKS)
        }
    }

    @Test
    fun movie4kResizerScalesTenBitTilesAcross34BitAnd32BitRings() {
        val dma = MockDmaMemory()
        val src = 0x1_a000_0000L
        val dst = 0x2e6c_5000L
        val srcStride = 0x9000
        val dstStride = 0x5600
        dma.map(src, 3 * srcStride)
        dma.map(dst, 2 * dstStride)
        dma.bytes(dst).fill(0x55)
        val input = ByteBuffer.wrap(dma.bytes(src)).order(ByteOrder.LITTLE_ENDIAN)
        fun luma(y: Int, x: Int) = 1 + y * 100 + x * 3
        fun tile(left: Int, chroma: Int, right: Int) = left or (chroma shl 10) or (right shl 20)
        for (y in 0 until 6) {
            for (x in 0 until 8 step 2) {
                val chroma = (if (y % 2 == 0) 521 + x else 642 + x) + (y / 2) * 4
                input.putInt((y / 2) * srcStride + (x / 2) * 8 + (y % 2) * 4,
                    tile(luma(y, x), chroma, luma(y, x + 1)))
            }
        }
        val scaler = ImageScalerE000(ImageScalerE000.IRQ132_PROFILE)
        fun write(offset: Int, value: Long) = scaler.access(0xfff7e000L + offset, true, value)
        write(0x008, 0x133)
        write(0x00c, 0x00050007) // 8 x 6 input, crop (1,1) to 6 x 4
        write(0x010, 0x00010001)
        write(0x014, 0x00030005)
        write(0x018, 0x00020002) // 3 x 3 output exercises both final tile edges
        write(0x0c0, src ushr 32)
        write(0x0c4, src and 0xffff_ffffL)
        write(0x0d0, srcStride.toLong())
        write(0x0d8, dst)
        write(0x0e4, dstStride.toLong())
        write(0x004, 1)
        var irqs = 0
        scaler.beforeInstruction(ImageScalerE000.COMPLETION_DELAY_TICKS, dma) { irqs++ }
        val output = ByteBuffer.wrap(dma.bytes(dst)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, irqs)
        for (row in 0..1) {
            val top = if (row == 0) 1 else 3
            val bottom = if (row == 0) 2 else 3
            val offset = row * dstStride
            val chromaDelta = if (row == 0) 2 else 4
            assertEquals(tile(luma(top, 1), 522 + chromaDelta, luma(top, 3)), output.getInt(offset))
            assertEquals(tile(luma(bottom, 1), 643 + chromaDelta, luma(bottom, 3)), output.getInt(offset + 4))
            assertEquals(tile(luma(top, 5), 525 + chromaDelta, luma(top, 5)), output.getInt(offset + 8))
            assertEquals(tile(luma(bottom, 5), 646 + chromaDelta, luma(bottom, 5)), output.getInt(offset + 12))
            assertTrue((offset + 16 until offset + dstStride).all { output.get(it) == 0x55.toByte() })
        }
    }

    @Test
    fun scalerE000Synthesizes34BitAxiSourceTo32BitUyvyAndChainsToPic0() {
        // BUG-A2-3: Exact GFX100S STILL / Multiple Exposure Frame 1 Review pipeline:
        // Stage 1: RECC_S 34-bit AXI 0x1_7300_0000 -> 32-bit DRAM 0x39F8_1000 (1456x1092, stride 3200)
        // Stage 2: 32-bit DRAM 0x39F8_1000 -> 32-bit LCDC1 PIC0 0x3946_A000 (1024x768, stride 3200)
        val currentTick = 860_000_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val stage1Dst = 0x39f8_1000L
        val pic0Dst = 0x3946_a000L
        val stride = 3200
        dma.map(stage1Dst, 1092 * stride)
        dma.map(pic0Dst, 768 * stride)

        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                // 1024x576 16-bit Bayer filled with bright neutral gray (~0xC000 -> sRGB > 200)
                val w = 1024
                val h = 576
                val buf = destination.order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until w * h) {
                    buf.putShort(0xC000.toShort())
                }
                return w * h * 2
            }
        }
        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val dmaCtx = object : DiscoveryContext {
            override fun core() = 0
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fcd94L
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
            override fun sensorPort() = sensor
        }
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerE000.KEY to ImageScalerE000.IRQ132_PROFILE)))
                .createBus(dmaCtx),
        )
        var seq = 1L
        // Stage 1: 34-bit AXI src 0x1_7300_0000 -> 32-bit DRAM 0x39F8_1000 (1456x1092, dstFmt=0)
        b.access(seq++, 0xfff7e008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7e00cL, 4, true, (8735L shl 16) or 11647L)
        b.access(seq++, 0xfff7e010L, 4, true, 0L)
        b.access(seq++, 0xfff7e014L, 4, true, (8735L shl 16) or 11647L)
        b.access(seq++, 0xfff7e018L, 4, true, (1091L shl 16) or 1455L)
        b.access(seq++, 0xfff7e0c0L, 4, true, 1L)
        b.access(seq++, 0xfff7e0c4L, 4, true, 0x7300_0000L)
        b.access(seq++, 0xfff7e0d0L, 4, true, 23296L)
        b.access(seq++, 0xfff7e0d4L, 4, true, 0L)
        b.access(seq++, 0xfff7e0d8L, 4, true, stage1Dst)
        b.access(seq++, 0xfff7e0e4L, 4, true, stride.toLong())
        b.access(seq++, 0xfff7e004L, 4, true, 1L)
        b.beforeInstruction(currentTick + ImageScalerE000.COMPLETION_DELAY_TICKS)
        assertEquals(listOf(132 to true), irqLog)
        b.access(seq++, 0xfff7e10cL, 4, true, 1L)

        // Stage 1 center pixel must be bright neutral UYVY (NOT all-zero!)
        val (s1Y, s1U, s1V) = readUyvyPixel(dma.bytes(stage1Dst), 728, 546, stride)
        assertTrue(s1Y > 180, "Stage 1 center luma must be bright from CMOS sensor (got Y=$s1Y)")
        assertTrue(s1U in 120..136 && s1V in 120..136, "Stage 1 center chroma must be neutral (got U=$s1U, V=$s1V)")

        // Stage 2: 32-bit DRAM 0x39F8_1000 (1456x1092) -> 32-bit PIC0 0x3946_A000 (1024x768)
        b.access(seq++, 0xfff7e008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7e00cL, 4, true, (1091L shl 16) or 1455L)
        b.access(seq++, 0xfff7e010L, 4, true, 0L)
        b.access(seq++, 0xfff7e014L, 4, true, (1091L shl 16) or 1455L)
        b.access(seq++, 0xfff7e018L, 4, true, (767L shl 16) or 1023L)
        b.access(seq++, 0xfff7e0c0L, 4, true, 0L)
        b.access(seq++, 0xfff7e0c4L, 4, true, stage1Dst)
        b.access(seq++, 0xfff7e0d0L, 4, true, stride.toLong())
        b.access(seq++, 0xfff7e0d4L, 4, true, 0L)
        b.access(seq++, 0xfff7e0d8L, 4, true, pic0Dst)
        b.access(seq++, 0xfff7e0e4L, 4, true, stride.toLong())
        b.access(seq++, 0xfff7e004L, 4, true, 1L)
        b.beforeInstruction(currentTick + 2 * ImageScalerE000.COMPLETION_DELAY_TICKS)

        val (p0Y, p0U, p0V) = readUyvyPixel(dma.bytes(pic0Dst), 512, 384, stride)
        assertTrue(p0Y > 180, "PIC0 center luma must carry synthesized captured photo (got Y=$p0Y)")
        assertTrue(p0U in 120..136 && p0V in 120..136, "PIC0 center chroma must be neutral (got U=$p0U, V=$p0V)")
    }

    @Test
    fun imageResultStatsBank1StrictRefusalAndReadOnlyZero() {
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(catalog, "strict", Path.of("."), mapOf("discovery" to mapOf(ImageResultStats.KEY to ImageResultStats.BANK1_PROFILE)))
        }
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageResultStats.KEY to ImageResultStats.BANK1_PROFILE)))
                .createBus(context),
        )
        assertEquals(0L, b.access(1L, 0xfff10400L, 4, false))
        assertEquals(0L, b.access(2L, 0xfff18060L, 4, false))
        assertEquals(0L, b.access(3L, 0xfff199a4L, 4, false))
        assertEquals(0L, b.access(4L, 0xfff199dcL, 4, false))
        assertFails { b.access(5L, 0xfff199a4L, 4, true, 1L) }

        val prior = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageResultStats.KEY to ImageResultStats.PROFILE)))
                .createBus(context),
        ).saveSnapshot()
        val promoted = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageResultStats.KEY to ImageResultStats.BANK1_PROFILE)))
                .createBus(context),
        )
        assertFailsWith<IllegalArgumentException> { promoted.prepareSnapshotRestore(prior, allowPromotedRegions = false) }
        promoted.prepareSnapshotRestore(prior, allowPromotedRegions = true)()
        assertEquals(0L, promoted.access(1L, 0xfff199a4L, 4, false))
    }

    @Test
    fun ispAutoSyncStrictRefusalTriggerSchedulingAndSnapshotRoundTrip() {
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(catalog, "strict", Path.of("."), mapOf("discovery" to mapOf(IspAutoSync.KEY to IspAutoSync.PROFILE)))
        }
        var currentTick = 900_000_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val enabledLines = mutableSetOf(100, 102, 108, 296)
        val highLines = mutableSetOf<Int>()
        val edgeLines = setOf(100, 102, 106, 108)
        val sink = object : com.github.nyabanban.fuji.emulator.api.EnabledEdgeInterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                irqLog += irq to high
                if (high) highLines += irq else highLines -= irq
            }
            override fun lineEnabled(irq: Int) = irq in enabledLines
            override fun edgeState(irq: Int) = com.github.nyabanban.fuji.emulator.api.EdgeInterruptState(
                high = irq in highLines,
                pending = false,
                edge = irq in edgeLines,
            )
        }
        val syncCtx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11b0000L
            override fun tick() = currentTick
            override fun interruptSink() = sink
        }
        fun syncBus(withAutoSync: Boolean = true): DiscoveryBus {
            val disc = linkedMapOf<String, Any>(
                "inputs" to mapOf(
                    RETAINED_69100_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    RETAINED_6C000_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0),
                ),
            )
            if (withAutoSync) disc[IspAutoSync.KEY] = IspAutoSync.PROFILE
            return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to disc)).createBus(syncCtx))
        }

        val b = syncBus()
        var seq = 1L
        // Writing 0xFFF6910C bit 1 schedules IRQ 296 level assert + status bit 1
        b.access(seq++, 0xfff6910cL, 4, true, 3L)
        val due296 = requireNotNull(b.nextDeadline())
        b.beforeInstruction(due296)
        assertEquals(listOf(296 to true), irqLog)
        assertEquals(2L, b.access(seq++, 0xfff69108L, 4, false) and 2L)
        // Guest ack clears level line 296
        b.access(seq++, 0xfff69108L, 4, true, 2L)
        assertEquals(listOf(296 to true, 296 to false), irqLog)

        // Writing 0xFFF6C140 bit 1:0 schedules IRQ 102 edge pulse
        currentTick = due296 + 10_000L
        b.access(seq++, 0xfff6c140L, 4, true, 3L)
        val due102 = requireNotNull(b.nextDeadline())
        b.beforeInstruction(due102)
        assertEquals(listOf(296 to true, 296 to false, 102 to true, 102 to false), irqLog)

        // Starting VSYNC TG (0xFFF6C184 = 1) schedules IRQ 100 and IRQ 108, and survives snapshot round-trip
        currentTick = due102 + 10_000L
        b.access(seq++, 0xfff6c184L, 4, true, 1L)
        val snap = b.saveSnapshot()
        val restored = syncBus()
        restored.prepareSnapshotRestore(snap, allowPromotedRegions = false)()
        val due100 = requireNotNull(restored.nextDeadline())
        restored.beforeInstruction(due100)
        assertTrue((100 to true) in irqLog && (100 to false) in irqLog)
        val due108 = due100 + 750_000L
        restored.beforeInstruction(due108)
        assertTrue((108 to true) in irqLog && (108 to false) in irqLog)

        // Promotion from legacy snapshot without ispAutoSync requires allowPromotedRegions = true
        val legacySnap = syncBus(withAutoSync = false).saveSnapshot()
        val promoted = syncBus(withAutoSync = true)
        assertFailsWith<IllegalArgumentException> { promoted.prepareSnapshotRestore(legacySnap, allowPromotedRegions = false) }
        promoted.prepareSnapshotRestore(legacySnap, allowPromotedRegions = true)()
    }

    @Test
    fun scalingMilbeautBlackMarginWordsProducesNeutralBlackWithoutGreenStripes() {
        // BUG-V3-8-4: Playback 9/100-frame grid clears PIC1 buffers with Milbeaut 0x80800000
        // ([00 00 80 80]) / 0x00008080 ([80 80 00 00]) / 0x00000000 before RESZ scaling.
        // Without normalizing black margin words to canonical UYVY [80 00 80 00], bilinear
        // scaling interpolates U=0 and alternating Y0=0/Y1=0x80 into olive-green stripes.
        val dma = MockDmaMemory()
        val srcAddr = 0x2b3a_2000L
        val dstAddr = 0x2c48_2000L
        val srcW = 320; val srcH = 240; val srcStride = srcW * 2
        val dstW = 240; val dstH = 180; val dstStride = dstW * 2
        dma.map(srcAddr, srcStride * srcH)
        dma.map(dstAddr, dstStride * dstH)
        val srcBuf = dma.bytes(srcAddr)
        for (y in 0 until srcH) {
            val row = y * srcStride
            for (x in 0 until srcW step 2) {
                val p = row + x * 2
                when {
                    x in 80 until 160 && y in 60 until 120 -> {
                        srcBuf[p] = 0xf0.toByte(); srcBuf[p + 1] = 0xf0.toByte()
                        srcBuf[p + 2] = 0x80.toByte(); srcBuf[p + 3] = 0x80.toByte()
                    }
                    y < 80 -> { // 0x80800000 LE = [00 00 80 80]
                        srcBuf[p] = 0x00; srcBuf[p + 1] = 0x00
                        srcBuf[p + 2] = 0x80.toByte(); srcBuf[p + 3] = 0x80.toByte()
                    }
                    y < 160 -> { // 0x00008080 LE = [80 80 00 00]
                        srcBuf[p] = 0x80.toByte(); srcBuf[p + 1] = 0x80.toByte()
                        srcBuf[p + 2] = 0x00; srcBuf[p + 3] = 0x00
                    }
                    else -> { // 0x00000000
                        srcBuf[p] = 0x00; srcBuf[p + 1] = 0x00
                        srcBuf[p + 2] = 0x00; srcBuf[p + 3] = 0x00
                    }
                }
            }
        }
        var tick = 1000L
        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) {} }
        val dmaCtx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fccd4L
            override fun tick() = tick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
        }
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerE000.KEY to ImageScalerE000.IRQ132_PROFILE)))
                .createBus(dmaCtx),
        )
        var seq = 1L
        b.access(seq++, 0xfff7e008L, 4, true, 0L)
        b.access(seq++, 0xfff7e00cL, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7e010L, 4, true, 0L)
        b.access(seq++, 0xfff7e014L, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7e018L, 4, true, ((dstH - 1).toLong() shl 16) or (dstW - 1).toLong())
        b.access(seq++, 0xfff7e0c0L, 4, true, 0L)
        b.access(seq++, 0xfff7e0c4L, 4, true, srcAddr)
        b.access(seq++, 0xfff7e0d0L, 4, true, srcStride.toLong())
        b.access(seq++, 0xfff7e0d4L, 4, true, 0L)
        b.access(seq++, 0xfff7e0d8L, 4, true, dstAddr)
        b.access(seq++, 0xfff7e0e4L, 4, true, dstStride.toLong())
        b.access(seq++, 0xfff7e004L, 4, true, 1L)
        b.beforeInstruction(tick + ImageScalerE000.COMPLETION_DELAY_TICKS)

        val out = dma.bytes(dstAddr)
        // Background points across all 3 margin regions must be canonical neutral black (Y=0, U=128, V=128)
        for ((x, y) in listOf(10 to 10, 200 to 20, 10 to 90, 200 to 90, 10 to 150, 200 to 150)) {
            assertEquals(Triple(0, 128, 128), readUyvyPixel(out, x, y, dstStride), "Background ($x,$y) must be canonical neutral black")
        }
        // Bright thumbnail interior must remain bright neutral white
        val (thumbY, thumbU, thumbV) = readUyvyPixel(out, 90, 68, dstStride)
        assertTrue(thumbY >= 235 && thumbU == 128 && thumbV == 128, "Thumbnail interior must stay white, got ($thumbY,$thumbU,$thumbV)")
    }

    @Test
    fun resizerAcceptsStridedUyvyAndSupports512MiB102MpRawConversionCeiling() {
        // Real GFX100S 102MP RAW conversion programs 11664x8764 -> 11648x8736 at stride 31,680 B:
        // srcSpan = 277,159,968 B, dstSpan = 276,748,096 B (both exceed legacy 256 MiB ceiling).
        var currentTick = 760_000_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val srcAddr = 0x3000_0000L
        val dstAddr = 0x3100_0000L
        val srcW = 64
        val srcH = 48
        val srcStride = 256 // strided > width*2
        val dstW = 48
        val dstH = 36
        val dstStride = 192
        dma.map(srcAddr, srcStride * srcH)
        dma.map(dstAddr, dstStride * dstH)
        fillUyvyQuadrants(dma.bytes(srcAddr), srcW, srcH, srcStride)

        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val dmaCtx = object : DiscoveryContext {
            override fun core() = 0
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fcce0L
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
        }
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerE000.KEY to ImageScalerE000.IRQ132_PROFILE)))
                .createBus(dmaCtx),
        )
        var seq = 1L
        b.access(seq++, 0xfff7e008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7e00cL, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7e010L, 4, true, 0L)
        b.access(seq++, 0xfff7e014L, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7e018L, 4, true, ((dstH - 1).toLong() shl 16) or (dstW - 1).toLong())
        b.access(seq++, 0xfff7e0c4L, 4, true, srcAddr)
        b.access(seq++, 0xfff7e0d0L, 4, true, srcStride.toLong())
        b.access(seq++, 0xfff7e0d8L, 4, true, dstAddr)
        b.access(seq++, 0xfff7e0e4L, 4, true, dstStride.toLong())
        b.access(seq++, 0xfff7e004L, 4, true, 1L)

        val due = assertNotNull(b.nextDeadline())
        b.beforeInstruction(due)
        assertEquals(listOf(132 to true), irqLog)
        val out = dma.bytes(dstAddr)
        assertEquals(Triple(40, 200, 100), readUyvyPixel(out, 8, 8, dstStride))
        assertEquals(Triple(240, 110, 160), readUyvyPixel(out, 36, 28, dstStride))
    }
}
