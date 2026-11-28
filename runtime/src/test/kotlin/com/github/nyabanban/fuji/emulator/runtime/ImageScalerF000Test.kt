package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class ImageScalerF000Test {
    private val catalog = AddressCatalog.bundled()
    private val context = object : DiscoveryContext {
        override fun core() = 1
        override fun pc() = 0x5afe0L
        override fun lr() = 0x11fdb18L
        override fun tick() = 868_271_299L
    }

    private fun bus(scaler: String? = ImageScalerF000.PROFILE, mode: String = "discovery"): DiscoveryBus {
        val discovery = linkedMapOf<String, Any>()
        if (scaler != null) discovery[ImageScalerF000.KEY] = scaler
        val config = if (discovery.isEmpty()) emptyMap() else mapOf("discovery" to discovery)
        return checkNotNull(CatalogRuntime(catalog, mode, Path.of("."), config).createBus(context))
    }

    @Test
    fun resetIdleBitsRetentionHolesAndSnapshotRoundTrip() {
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(catalog, "strict", Path.of("."), mapOf("discovery" to mapOf(ImageScalerF000.KEY to ImageScalerF000.PROFILE)))
        }
        val b = bus()
        var seq = 1L
        // Initial read of F0DC/F0E0 returns 0; F0E4 sees both idle bits (0x01000100)
        assertEquals(0L, b.access(seq++, 0xfff7f0dcL, 4, false))
        assertEquals(0L, b.access(seq++, 0xfff7f0e0L, 4, false))
        assertEquals(0x01000100L, b.access(seq++, 0xfff7f0e4L, 4, false))

        // Enabling DC bit 28 clears F0E4 bit 8; enabling E0 bits 31:28 clears F0E4 bit 24
        b.access(seq++, 0xfff7f0dcL, 4, true, 0x10000011L)
        assertEquals(0x01000000L, b.access(seq++, 0xfff7f0e4L, 4, false))
        b.access(seq++, 0xfff7f0e0L, 4, true, 0x30000022L)
        assertEquals(0L, b.access(seq++, 0xfff7f0e4L, 4, false))

        // Clearing both restores 0x01000100 (011FDB00..011FDB68 sequence)
        b.access(seq++, 0xfff7f0dcL, 4, true, 0x00000011L)
        b.access(seq++, 0xfff7f0e0L, 4, true, 0x00000022L)
        assertEquals(0x01000100L, b.access(seq++, 0xfff7f0e4L, 4, false))

        // Reset at F000: write 0, read asserts bit 0 == 1 (011FDB70..011FDB90)
        b.access(seq++, 0xfff7f000L, 4, true, 0L)
        assertEquals(1L, b.access(seq++, 0xfff7f000L, 4, false))

        // Retention across normal words and tail F100/F104
        b.access(seq++, 0xfff7f004L, 4, true, 0x12345678L)
        b.access(seq++, 0xfff7f100L, 4, true, 0x0000abcdL)
        b.access(seq++, 0xfff7f104L, 4, true, 0x0000ef01L)
        assertEquals(0x12345678L, b.access(seq++, 0xfff7f004L, 4, false))
        assertEquals(0x0000abcdL, b.access(seq++, 0xfff7f100L, 4, false))
        assertEquals(0x0000ef01L, b.access(seq++, 0xfff7f104L, 4, false))

        // Snapshot round-trip
        val saved = b.saveSnapshot()
        val restored = bus()
        restored.prepareSnapshotRestore(saved, allowPromotedRegions = false)()
        assertEquals(0x12345678L, restored.access(seq++, 0xfff7f004L, 4, false))
        assertEquals(0x0000abcdL, restored.access(seq++, 0xfff7f100L, 4, false))
        assertEquals(0x01000100L, restored.access(seq++, 0xfff7f0e4L, 4, false))

        // Hole [0xFFF7F0EC, 0xFFF7F100) must refuse
        assertFails { b.access(seq++, 0xfff7f0ecL, 4, false) }
    }

    @Test
    fun promotesFromSnapshotWithoutScalerWhenAllowed() {
        val emptyBus = bus(scaler = null)
        val saved = emptyBus.saveSnapshot()

        val promoted = bus(scaler = ImageScalerF000.PROFILE)
        assertFailsWith<IllegalArgumentException> {
            promoted.prepareSnapshotRestore(saved, allowPromotedRegions = false)
        }
        promoted.prepareSnapshotRestore(saved, allowPromotedRegions = true)()
        assertEquals(0x01000100L, promoted.access(1L, 0xfff7f0e4L, 4, false))
    }

    @Test
    fun imageResultStatsProvidesReadOnlyZeroPingPongWindowsAndPromotesCleanly() {
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(catalog, "strict", Path.of("."), mapOf("discovery" to mapOf(ImageResultStats.KEY to ImageResultStats.PROFILE)))
        }
        val emptyBus = bus(scaler = null)
        val saved = emptyBus.saveSnapshot()

        val withStats = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageResultStats.KEY to ImageResultStats.PROFILE))).createBus(context),
        )
        assertFailsWith<IllegalArgumentException> {
            withStats.prepareSnapshotRestore(saved, allowPromotedRegions = false)
        }
        withStats.prepareSnapshotRestore(saved, allowPromotedRegions = true)()
        assertEquals(0L, withStats.access(1L, 0xfff16000L, 4, false))
        assertEquals(0L, withStats.access(2L, 0xfff16ffcL, 4, false))
        assertEquals(0L, withStats.access(3L, 0xfff1d000L, 4, false))
        assertEquals(0L, withStats.access(4L, 0xfff1dffcL, 4, false))
        assertFails { withStats.access(5L, 0xfff16000L, 4, true, 1L) }
    }

    @Test
    fun irq133CompletionProfileRequiresSetupAssertsAfterDelayClearsOnF0e4AndPromotesPendingKick() {
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(catalog, "strict", Path.of("."), mapOf("discovery" to mapOf(ImageScalerF000.KEY to ImageScalerF000.IRQ133_PROFILE)))
        }
        var currentTick = 868_273_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                irqLog += irq to high
            }
        }
        val liveContext = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fdbbcL
            override fun tick() = currentTick
            override fun interruptSink() = sink
        }
        fun irqBus(profile: String = ImageScalerF000.IRQ133_PROFILE): DiscoveryBus = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerF000.KEY to profile)))
                .createBus(liveContext),
        )

        val b = irqBus()
        var seq = 1L
        // Kicking F004 before F0C0..D0 setup must not arm IRQ 133
        b.access(seq++, 0xfff7f004L, 4, true, 1L)
        assertNull(b.nextDeadline())
        b.beforeInstruction(currentTick + 1000L)
        assertTrue(irqLog.isEmpty())

        // Setup F0C4/F0D0 and kick F004 bit 0
        b.access(seq++, 0xfff7f0c4L, 4, true, 0x8999c000L)
        b.access(seq++, 0xfff7f0d0L, 4, true, 0x8a9dac00L)
        b.access(seq++, 0xfff7f004L, 4, true, 1L)
        val due = currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS
        assertEquals(due, b.nextDeadline())

        // Before deadline: IRQ not asserted yet
        b.beforeInstruction(due - 1L)
        assertTrue(irqLog.isEmpty())

        // Save pending snapshot, restore, and fire at due tick
        val pendingSnap = b.saveSnapshot()
        val restoredPending = irqBus()
        restoredPending.prepareSnapshotRestore(pendingSnap, allowPromotedRegions = false)()
        assertEquals(due, restoredPending.nextDeadline())
        restoredPending.beforeInstruction(due)
        assertEquals(listOf(133 to true), irqLog)
        assertNull(restoredPending.nextDeadline())
        assertEquals(0x01000101L, restoredPending.access(seq++, 0xfff7f0e4L, 4, false))

        // Writing 1 to F0E4 acks and deasserts GIC line 133
        restoredPending.access(seq++, 0xfff7f0e4L, 4, true, 1L)
        assertEquals(listOf(133 to true, 133 to false), irqLog)
        assertEquals(0x01000100L, restoredPending.access(seq++, 0xfff7f0e4L, 4, false))

        // Legacy PROFILE snapshot with pending F004=1 + F0C4/D0 setup promotes cleanly to IRQ133_PROFILE
        irqLog.clear()
        val legacy = irqBus(ImageScalerF000.PROFILE)
        legacy.access(1L, 0xfff7f0c4L, 4, true, 0x8999c000L)
        legacy.access(2L, 0xfff7f0d0L, 4, true, 0x8a9dac00L)
        legacy.access(3L, 0xfff7f0e4L, 4, true, 1L)
        legacy.access(4L, 0xfff7f004L, 4, true, 1L)
        val legacySnap = legacy.saveSnapshot()

        currentTick = 871_700_000L
        assertFailsWith<IllegalArgumentException> {
            irqBus().prepareSnapshotRestore(legacySnap, allowPromotedRegions = false)
        }
        val promoted = irqBus()
        promoted.prepareSnapshotRestore(legacySnap, allowPromotedRegions = true)()
        val promotedDue = currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS
        assertEquals(promotedDue, promoted.nextDeadline())
        promoted.beforeInstruction(promotedDue)
        assertEquals(listOf(133 to true), irqLog)
        promoted.access(5L, 0xfff7f0e4L, 4, true, 1L)
        assertEquals(listOf(133 to true, 133 to false), irqLog)

        // Downgrading IRQ133_PROFILE -> PROFILE must be rejected
        assertFailsWith<IllegalArgumentException> {
            irqBus(ImageScalerF000.PROFILE).prepareSnapshotRestore(promoted.saveSnapshot(), allowPromotedRegions = true)
        }
    }

    private class MockDmaMemory : DmaMemory {
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
            val (base, arr) = locate(address, len.toLong())
            destination.put(arr, (address - base).toInt(), len)
        }
        override fun write(address: Long, source: ByteBuffer) {
            val len = source.remaining()
            val (base, arr) = locate(address, len.toLong())
            source.get(arr, (address - base).toInt(), len)
        }
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
    fun reusedScratchRespectsSmallerDmaSpansAndPadding() {
        val src = 0x1000L
        val dst = 0x2000L
        val dma = MockDmaMemory().apply {
            map(src, 256)
            map(dst, 256)
        }
        fun transform(scaler: ImageScalerF000, width: Int, height: Int, stride: Int): ByteArray {
            fun write(offset: Long, value: Long) {
                scaler.access(ImageScalerF000.BASE + offset, true, value)
            }
            val dimensions = ((height - 1L) shl 16) or (width - 1L)
            write(0x0c, dimensions)
            write(0x14, dimensions)
            write(0x18, dimensions)
            write(0xc4, src)
            write(0xc8, width * 2L)
            write(0xd0, dst)
            write(0xd4, stride.toLong())
            write(0x04, 1L)
            dma.bytes(dst).fill(0x5a)
            scaler.beforeInstruction(ImageScalerF000.COMPLETION_DELAY_TICKS, dma) {}
            write(0xe4, 1L)
            return dma.bytes(dst).copyOf()
        }
        val reused = ImageScalerF000(ImageScalerF000.IRQ133_PROFILE)
        for ((width, height, stride) in listOf(Triple(8, 9, 16), Triple(5, 3, 14), Triple(6, 2, 12))) {
            kotlin.random.Random(width).nextBytes(dma.bytes(src))
            val expected = transform(ImageScalerF000(ImageScalerF000.IRQ133_PROFILE), width, height, stride)
            assertContentEquals(expected, transform(reused, width, height, stride))
        }
    }

    @Test
    fun pixelDmaScalesUyvyMemYcToMemDiLcdallAndRaisesIrq133() {
        var currentTick = 868_300_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val srcAddr = 0x8996_0000L
        val dstAddr = 0x8a9c_8000L
        val srcW = 728
        val srcH = 546
        val srcStride = 1472 // aligned 736 * 2
        val dstW = 640
        val dstH = 480
        val dstStride = 1280
        dma.map(srcAddr, srcStride * srcH)
        dma.map(dstAddr, dstStride * dstH)
        fillUyvyQuadrants(dma.bytes(srcAddr), srcW, srcH, srcStride)

        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val dmaCtx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fdbbcL
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
        }
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerF000.KEY to ImageScalerF000.IRQ133_PROFILE)))
                .createBus(dmaCtx),
        )
        var seq = 1L
        b.access(seq++, 0xfff7f008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7f00cL, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7f010L, 4, true, 0L)
        b.access(seq++, 0xfff7f014L, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7f018L, 4, true, ((dstH - 1).toLong() shl 16) or (dstW - 1).toLong())
        b.access(seq++, 0xfff7f0c4L, 4, true, srcAddr)
        b.access(seq++, 0xfff7f0c8L, 4, true, srcStride.toLong())
        b.access(seq++, 0xfff7f0d0L, 4, true, dstAddr)
        b.access(seq++, 0xfff7f0d4L, 4, true, dstStride.toLong())
        b.access(seq++, 0xfff7f004L, 4, true, 1L)

        val due = currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS
        b.beforeInstruction(due - 1L)
        assertTrue(irqLog.isEmpty())
        assertTrue(dma.bytes(dstAddr).all { it == 0.toByte() }, "Destination must stay untouched before due tick")

        b.beforeInstruction(due)
        assertEquals(listOf(133 to true), irqLog)
        val out = dma.bytes(dstAddr)
        assertEquals(Triple(40, 200, 100), readUyvyPixel(out, 100, 80, dstStride))
        assertEquals(Triple(110, 80, 210), readUyvyPixel(out, 540, 80, dstStride))
        assertEquals(Triple(180, 140, 60), readUyvyPixel(out, 100, 400, dstStride))
        assertEquals(Triple(240, 110, 160), readUyvyPixel(out, 540, 400, dstStride))

        b.access(seq++, 0xfff7f0e4L, 4, true, 1L)
        assertEquals(listOf(133 to true, 133 to false), irqLog)
    }

    @Test
    fun pixelDmaSupportsPreDecimationAndCrop() {
        var currentTick = 868_400_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val srcAddr = 0x8996_0000L
        val dstAddr = 0x8a9c_8000L
        val srcW = 128
        val srcH = 128
        val srcStride = srcW * 2
        val dstW = 64
        val dstH = 64
        val dstStride = dstW * 2
        dma.map(srcAddr, srcStride * srcH)
        dma.map(dstAddr, dstStride * dstH + 64)
        fillUyvyQuadrants(dma.bytes(srcAddr), srcW, srcH, srcStride)

        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val dmaCtx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fdbbcL
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
        }
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerF000.KEY to ImageScalerF000.IRQ133_PROFILE)))
                .createBus(dmaCtx),
        )
        // Crop bottom-right quadrant [64..128) x [64..128) with hDecim=1, vDecim=1 -> cropWDecim=32, cropHDecim=32
        var seq = 1L
        b.access(seq++, 0xfff7f008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7f00cL, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7f010L, 4, true, (64L shl 16) or 64L)
        b.access(seq++, 0xfff7f014L, 4, true, (31L shl 16) or 31L)
        b.access(seq++, 0xfff7f018L, 4, true, ((dstH - 1).toLong() shl 16) or (dstW - 1).toLong())
        b.access(seq++, 0xfff7f01cL, 4, true, (1L shl 16) or 0x1000L)
        b.access(seq++, 0xfff7f020L, 4, true, (1L shl 16) or 0x1000L)
        b.access(seq++, 0xfff7f0c4L, 4, true, srcAddr)
        b.access(seq++, 0xfff7f0c8L, 4, true, srcStride.toLong())
        b.access(seq++, 0xfff7f0d0L, 4, true, dstAddr)
        b.access(seq++, 0xfff7f0d4L, 4, true, dstStride.toLong())
        b.access(seq++, 0xfff7f004L, 4, true, 1L)
        b.beforeInstruction(currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS)
        assertEquals(listOf(133 to true), irqLog)
        val out = dma.bytes(dstAddr)
        assertEquals(Triple(240, 110, 160), readUyvyPixel(out, 16, 16, dstStride))
        assertEquals(Triple(240, 110, 160), readUyvyPixel(out, 48, 48, dstStride))
    }

    @Test
    fun pixelDmaValidatesDmaMemoryAndUnmappedBoundsAndCancelOnReset() {
        var currentTick = 868_500_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val srcAddr = 0x8996_0000L
        val dstAddr = 0x8a9c_8000L
        dma.map(srcAddr, 64 * 64 * 2)
        dma.map(dstAddr, 64 * 64 * 2)

        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        var exposedDma: DmaMemory? = null
        val ctx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fdbbcL
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = exposedDma
        }
        fun makeBus() = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerF000.KEY to ImageScalerF000.IRQ133_PROFILE)))
                .createBus(ctx),
        )
        fun setup(b: DiscoveryBus, f008: Long = 0x100L, dst: Long = dstAddr) {
            var s = 1L
            b.access(s++, 0xfff7f008L, 4, true, f008)
            b.access(s++, 0xfff7f00cL, 4, true, (63L shl 16) or 63L)
            b.access(s++, 0xfff7f014L, 4, true, (63L shl 16) or 63L)
            b.access(s++, 0xfff7f018L, 4, true, (63L shl 16) or 63L)
            b.access(s++, 0xfff7f0c4L, 4, true, srcAddr)
            b.access(s++, 0xfff7f0c8L, 4, true, 128L)
            b.access(s++, 0xfff7f0d0L, 4, true, dst)
            b.access(s++, 0xfff7f0d4L, 4, true, 128L)
            b.access(s++, 0xfff7f004L, 4, true, 1L)
        }

        // 1. Missing DmaMemory when stride > 0 fails loudly before asserting IRQ 133
        val bMissing = makeBus()
        setup(bMissing)
        assertFailsWith<IllegalArgumentException> { bMissing.beforeInstruction(currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS) }
        assertTrue(irqLog.isEmpty())

        // 2. Unsupported F008 format bits fail loudly before asserting IRQ 133
        exposedDma = dma
        for (badFmt in listOf(0x101L, 0x110L, 0x111L)) {
            val bFmt = makeBus()
            setup(bFmt, f008 = badFmt)
            assertFailsWith<IllegalArgumentException> { bFmt.beforeInstruction(currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS) }
            assertTrue(irqLog.isEmpty())
        }

        // 3. Unmapped destination bounds fail loudly before asserting IRQ 133
        val bUnmapped = makeBus()
        setup(bUnmapped, dst = 0x9000_0000L)
        assertFailsWith<IllegalArgumentException> { bUnmapped.beforeInstruction(currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS) }
        assertTrue(irqLog.isEmpty())

        // 4. Reset F000=0 before due cancels pending DMA without touching dst RAM
        val bCancel = makeBus()
        fillUyvyQuadrants(dma.bytes(srcAddr), 64, 64, 128)
        setup(bCancel)
        bCancel.access(99L, 0xfff7f000L, 4, true, 0L)
        assertNull(bCancel.nextDeadline())
        bCancel.beforeInstruction(currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS)
        assertTrue(irqLog.isEmpty())
        assertTrue(dma.bytes(dstAddr).all { it == 0.toByte() })

        // 5. Huge DMA span (> MAX_PIXEL_DMA_BYTES) fails loudly before DmaMemory.check or IRQ 133
        val bHuge = makeBus()
        var sh = 1L
        bHuge.access(sh++, 0xfff7f008L, 4, true, 0x100L)
        bHuge.access(sh++, 0xfff7f00cL, 4, true, (1023L shl 16) or 1023L)
        bHuge.access(sh++, 0xfff7f014L, 4, true, (1023L shl 16) or 1023L)
        bHuge.access(sh++, 0xfff7f018L, 4, true, (1023L shl 16) or 1023L)
        bHuge.access(sh++, 0xfff7f0c4L, 4, true, srcAddr)
        bHuge.access(sh++, 0xfff7f0c8L, 4, true, 0x3fffcL)
        bHuge.access(sh++, 0xfff7f0d0L, 4, true, dstAddr)
        bHuge.access(sh++, 0xfff7f0d4L, 4, true, 0x3fffcL)
        bHuge.access(sh++, 0xfff7f004L, 4, true, 1L)
        assertFailsWith<IllegalArgumentException> {
            bHuge.beforeInstruction(currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS)
        }
        assertTrue(irqLog.isEmpty())
    }

    @Test
    fun pixelDmaPreservesLastRowChromaOnOddCropWidth() {
        val currentTick = 868_600_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val srcAddr = 0x8996_0000L
        val dstAddr = 0x8a9c_8000L
        val srcW = 4
        val srcH = 4
        val srcStride = srcW * 2
        val dstW = 4
        val dstH = 4
        val dstStride = dstW * 2
        dma.map(srcAddr, srcStride * srcH)
        dma.map(dstAddr, dstStride * dstH)

        // Uniform red YYUV422 (Y=89, U=94, V=228) across entire 4x4 source
        val src = dma.bytes(srcAddr)
        for (y in 0 until srcH) {
            for (x in 0 until srcW step 2) {
                val p = y * srcStride + x * 2
                src[p] = 89.toByte()
                src[p + 1] = 89.toByte()
                src[p + 2] = 94.toByte()
                src[p + 3] = 228.toByte()
            }
        }

        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val ctx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fdbbcL
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
        }
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerF000.KEY to ImageScalerF000.IRQ133_PROFILE)))
                .createBus(ctx),
        )
        var seq = 1L
        b.access(seq++, 0xfff7f008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7f00cL, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7f010L, 4, true, 0L)
        b.access(seq++, 0xfff7f014L, 4, true, ((4 - 1).toLong() shl 16) or (3 - 1).toLong())
        b.access(seq++, 0xfff7f018L, 4, true, ((dstH - 1).toLong() shl 16) or (dstW - 1).toLong())
        b.access(seq++, 0xfff7f0c4L, 4, true, srcAddr)
        b.access(seq++, 0xfff7f0c8L, 4, true, srcStride.toLong())
        b.access(seq++, 0xfff7f0d0L, 4, true, dstAddr)
        b.access(seq++, 0xfff7f0d4L, 4, true, dstStride.toLong())
        b.access(seq++, 0xfff7f004L, 4, true, 1L)
        b.beforeInstruction(currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS)
        assertEquals(listOf(133 to true), irqLog)

        val out = dma.bytes(dstAddr)
        assertEquals(Triple(89, 94, 228), readUyvyPixel(out, 0, 0, dstStride))
        assertEquals(Triple(89, 94, 228), readUyvyPixel(out, 2, 0, dstStride))
        assertEquals(Triple(89, 94, 228), readUyvyPixel(out, 2, 3, dstStride))
        assertEquals(Triple(89, 94, 228), readUyvyPixel(out, 3, 3, dstStride))
    }

    @Test
    fun pixelDmaRespectsExplicitOneByOneCropWhenF014ZeroWritten() {
        val currentTick = 868_700_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val srcAddr = 0x8996_0000L
        val dstAddr = 0x8a9c_8000L
        val srcW = 8
        val srcH = 8
        val srcStride = srcW * 2
        val dstW = 4
        val dstH = 4
        val dstStride = dstW * 2
        dma.map(srcAddr, srcStride * srcH)
        dma.map(dstAddr, dstStride * dstH)

        // Fill 8x8 source with green (Y=150, U=44, V=21), then set pixel (0,0) pair to red (Y0=89, Y1=150, U=94, V=228)
        val src = dma.bytes(srcAddr)
        for (y in 0 until srcH) {
            for (x in 0 until srcW step 2) {
                val p = y * srcStride + x * 2
                src[p] = 150.toByte()
                src[p + 1] = 150.toByte()
                src[p + 2] = 44.toByte()
                src[p + 3] = 21.toByte()
            }
        }
        src[0] = 89.toByte()
        src[1] = 150.toByte()
        src[2] = 94.toByte()
        src[3] = 228.toByte()

        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val ctx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fdbbcL
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
        }
        fun makeIrqBus() = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerF000.KEY to ImageScalerF000.IRQ133_PROFILE)))
                .createBus(ctx),
        )
        val b = makeIrqBus()
        var seq = 1L
        b.access(seq++, 0xfff7f008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7f00cL, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7f010L, 4, true, 0L)
        b.access(seq++, 0xfff7f014L, 4, true, 0L) // explicit 1x1 crop
        b.access(seq++, 0xfff7f018L, 4, true, ((dstH - 1).toLong() shl 16) or (dstW - 1).toLong())
        b.access(seq++, 0xfff7f0c4L, 4, true, srcAddr)
        b.access(seq++, 0xfff7f0c8L, 4, true, srcStride.toLong())
        b.access(seq++, 0xfff7f0d0L, 4, true, dstAddr)
        b.access(seq++, 0xfff7f0d4L, 4, true, dstStride.toLong())

        // Verify snapshot/restore preserves explicit F014=0L 1x1 crop intent
        val snap = b.saveSnapshot()
        val restored = makeIrqBus()
        restored.prepareSnapshotRestore(snap, allowPromotedRegions = false)()
        restored.access(seq++, 0xfff7f004L, 4, true, 1L)
        restored.beforeInstruction(currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS)
        assertEquals(listOf(133 to true), irqLog)

        val out = dma.bytes(dstAddr)
        assertEquals(Triple(89, 94, 228), readUyvyPixel(out, 0, 0, dstStride))
        assertEquals(Triple(89, 94, 228), readUyvyPixel(out, 3, 3, dstStride))
    }

    @Test
    fun pixelDmaResetClearsF004Bit0SoPromotedSnapshotDoesNotRearmCancelledDma() {
        var currentTick = 868_800_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val srcAddr = 0x8996_0000L
        val dstAddr = 0x8a9c_8000L
        dma.map(srcAddr, 16 * 16 * 2)
        dma.map(dstAddr, 16 * 16 * 2)
        fillUyvyQuadrants(dma.bytes(srcAddr), 16, 16, 32)

        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val ctx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fdbbcL
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
        }
        fun makeBus(profile: String) = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerF000.KEY to profile)))
                .createBus(ctx),
        )

        for (sourceProfile in listOf(ImageScalerF000.IRQ133_PROFILE, ImageScalerF000.PROFILE)) {
            irqLog.clear()
            dma.bytes(dstAddr).fill(0)
            val b = makeBus(sourceProfile)
            var seq = 1L
            b.access(seq++, 0xfff7f008L, 4, true, 0x100L)
            b.access(seq++, 0xfff7f00cL, 4, true, (15L shl 16) or 15L)
            b.access(seq++, 0xfff7f014L, 4, true, (15L shl 16) or 15L)
            b.access(seq++, 0xfff7f018L, 4, true, (15L shl 16) or 15L)
            b.access(seq++, 0xfff7f0c4L, 4, true, srcAddr)
            b.access(seq++, 0xfff7f0c8L, 4, true, 32L)
            b.access(seq++, 0xfff7f0d0L, 4, true, dstAddr)
            b.access(seq++, 0xfff7f0d4L, 4, true, 32L)
            b.access(seq++, 0xfff7f004L, 4, true, 1L)
            b.access(seq++, 0xfff7f000L, 4, true, 0L)
            assertNull(b.nextDeadline())

            val snap = b.saveSnapshot()
            currentTick += 1000L
            val restored = makeBus(ImageScalerF000.IRQ133_PROFILE)
            restored.prepareSnapshotRestore(snap, allowPromotedRegions = true)()
            assertNull(restored.nextDeadline(), "Cancelled DMA from $sourceProfile must not re-arm on promoted restore")
            restored.beforeInstruction(currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS)
            assertTrue(irqLog.isEmpty())
            assertTrue(dma.bytes(dstAddr).all { it == 0.toByte() })
        }
    }

    @Test
    fun pixelDmaPreservesDestinationStridePaddingBytes() {
        val currentTick = 868_900_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val srcAddr = 0x8996_0000L
        val dstAddr = 0x8a9c_8000L
        val srcW = 16
        val srcH = 8
        val srcStride = srcW * 2
        val dstW = 8
        val dstH = 4
        val dstStride = 32 // > dstW * 2 (16 bytes)
        val tailBytes = 64
        dma.map(srcAddr, srcStride * srcH)
        dma.map(dstAddr, dstStride * dstH + tailBytes)
        fillUyvyQuadrants(dma.bytes(srcAddr), srcW, srcH, srcStride)
        val sentinel = 0xA5.toByte()
        dma.bytes(dstAddr).fill(sentinel)

        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val ctx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fdbbcL
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
        }
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerF000.KEY to ImageScalerF000.IRQ133_PROFILE)))
                .createBus(ctx),
        )
        var seq = 1L
        b.access(seq++, 0xfff7f008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7f00cL, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7f014L, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7f018L, 4, true, ((dstH - 1).toLong() shl 16) or (dstW - 1).toLong())
        b.access(seq++, 0xfff7f0c4L, 4, true, srcAddr)
        b.access(seq++, 0xfff7f0c8L, 4, true, srcStride.toLong())
        b.access(seq++, 0xfff7f0d0L, 4, true, dstAddr)
        b.access(seq++, 0xfff7f0d4L, 4, true, dstStride.toLong())
        b.access(seq++, 0xfff7f004L, 4, true, 1L)
        b.beforeInstruction(currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS)
        assertEquals(listOf(133 to true), irqLog)

        val out = dma.bytes(dstAddr)
        assertEquals(Triple(40, 200, 100), readUyvyPixel(out, 0, 0, dstStride))
        assertEquals(Triple(240, 110, 160), readUyvyPixel(out, dstW - 1, dstH - 1, dstStride))
        for (y in 0 until dstH) {
            val rowOff = y * dstStride
            for (xByte in (dstW * 2) until dstStride) {
                assertEquals(sentinel, out[rowOff + xByte], "Row $y padding byte $xByte must remain untouched")
            }
        }
        for (i in (dstStride * dstH) until out.size) {
            assertEquals(sentinel, out[i], "Tail byte $i must remain untouched")
        }
    }

    @Test
    fun pixelDmaEmitsYyuv422WhenF0d8Is0x88ForFaceAndEyeAfBuffer() {
        val currentTick = 869_000_000L
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val dma = MockDmaMemory()
        val srcAddr = 0x8999_c000L
        val dstAddr = 0x8a9d_ac00L
        val srcW = 16
        val srcH = 8
        val srcStride = srcW * 2
        val dstW = 8
        val dstH = 4
        val dstStride = dstW * 2
        dma.map(srcAddr, srcStride * srcH)
        dma.map(dstAddr, dstStride * dstH)
        fillUyvyQuadrants(dma.bytes(srcAddr), srcW, srcH, srcStride)

        val sink = object : InterruptSink { override fun setLine(irq: Int, high: Boolean) { irqLog += irq to high } }
        val ctx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11fdbbcL
            override fun tick() = currentTick
            override fun interruptSink() = sink
            override fun dmaMemory() = dma
        }
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to mapOf(ImageScalerF000.KEY to ImageScalerF000.IRQ133_PROFILE)))
                .createBus(ctx),
        )
        var seq = 1L
        b.access(seq++, 0xfff7f008L, 4, true, 0x100L)
        b.access(seq++, 0xfff7f00cL, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7f014L, 4, true, ((srcH - 1).toLong() shl 16) or (srcW - 1).toLong())
        b.access(seq++, 0xfff7f018L, 4, true, ((dstH - 1).toLong() shl 16) or (dstW - 1).toLong())
        b.access(seq++, 0xfff7f0c4L, 4, true, srcAddr)
        b.access(seq++, 0xfff7f0c8L, 4, true, srcStride.toLong())
        b.access(seq++, 0xfff7f0d0L, 4, true, dstAddr)
        b.access(seq++, 0xfff7f0d4L, 4, true, dstStride.toLong())
        b.access(seq++, 0xfff7f0d8L, 4, true, 0x88L) // YYUV422 [Y0, Y1, U, V]
        b.access(seq++, 0xfff7f004L, 4, true, 1L)
        b.beforeInstruction(currentTick + ImageScalerF000.COMPLETION_DELAY_TICKS)
        assertEquals(listOf(133 to true), irqLog)

        val out = dma.bytes(dstAddr)
        // Top-left quadrant: Y=40, U=200, V=100 -> YYUV422 bytes [40, 40, 200, 100]
        assertEquals(40, out[0].toInt() and 0xff)
        assertEquals(40, out[1].toInt() and 0xff)
        assertEquals(200, out[2].toInt() and 0xff)
        assertEquals(100, out[3].toInt() and 0xff)
        // Bottom-right quadrant: Y=240, U=110, V=160 -> YYUV422 bytes [240, 240, 110, 160]
        val brOff = (dstH - 1) * dstStride + ((dstW - 2) / 2) * 4
        assertEquals(240, out[brOff].toInt() and 0xff)
        assertEquals(240, out[brOff + 1].toInt() and 0xff)
        assertEquals(110, out[brOff + 2].toInt() and 0xff)
        assertEquals(160, out[brOff + 3].toInt() and 0xff)
    }
}
